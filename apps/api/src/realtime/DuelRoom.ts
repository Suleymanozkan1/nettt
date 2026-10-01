import { Room, ServerError, type AuthContext, type Client } from 'colyseus';
import { MapSchema, Schema, type } from '@colyseus/schema';
import { randomInt } from 'node:crypto';
import { createVerifier } from 'fast-jwt';
import type { Prisma, PrismaClient } from '@prisma/client';
import {
  DEFAULT_PARAMS, DUEL_COUNTDOWN_MS, DUEL_MAX_MS, DUEL_MAX_PLAYERS, DUEL_MIN_PLAYERS, DUEL_REWARDED_WINS_PER_DAY,
  DUEL_TAP_LAG_MS, DUEL_TAP_LEAD_MS, DUEL_WIN_CREDITS, RunSim,
} from '@stage/shared';
import type { Config } from '../config';
import { grant, rewardsPaused } from '../ledger';
import { metrics } from '../metrics';

export class DuelPlayer extends Schema {
  @type('string') name = '';
  @type('string') character = 'char_fox';
  @type('number') score = 0;
  @type('number') fits = 0;
  @type('number') lives = 0;
  @type('number') combo = 0;
  @type('boolean') alive = true;
  @type('boolean') connected = true;
}

export class DuelState extends Schema {
  @type('string') phase: 'waiting' | 'countdown' | 'playing' | 'finished' = 'waiting';
  @type('number') seed = 0;
  @type('number') countdownMs = 0;
  @type('string') winner = '';
  @type({ map: DuelPlayer }) players = new MapSchema<DuelPlayer>();
}

interface Seat { userId: string; sim: RunSim | null; queue: number[] }
interface AuthData { userId: string; name: string; character: string }

export interface DuelDeps { prisma: PrismaClient; config: Config }

/**
 * Live duel: 2–4 players play the same seed at the same time. Every player's run is simulated on the
 * server (RunSim from @stage/shared); clients only send tap timestamps, which are bounded by the
 * server clock. Pattern after colyseus/tutorial-phaser Part 4: inputs are queued in onMessage and
 * consumed in a fixed simulation tick.
 */
export class DuelRoom extends Room<DuelState> {
  static deps: DuelDeps;
  maxClients = DUEL_MAX_PLAYERS;
  private seats = new Map<string, Seat>();
  private startedAt = 0;
  private countdown: { clear(): void } | null = null;
  private verify!: (token: string) => { sub: string; tv?: number };

  onCreate(): void {
    this.setState(new DuelState());
    this.state.seed = randomInt(0, 2 ** 31);
    this.verify = createVerifier({ key: DuelRoom.deps.config.JWT_SECRET, algorithms: ['HS256'] }) as typeof this.verify;
    this.onMessage('tap', (client, msg: unknown) => {
      const t = (msg as { t?: unknown } | null)?.t;
      const seat = this.seats.get(client.sessionId);
      if (!seat || this.state.phase !== 'playing' || typeof t !== 'number' || !Number.isInteger(t) || seat.queue.length > 20) return;
      seat.queue.push(t);
    });
    this.setSimulationInterval(() => this.tick(), 100);
    metrics.duelRooms.inc();
  }

  async onAuth(_client: Client, _options: unknown, context: AuthContext): Promise<AuthData> {
    let payload: { sub: string; tv?: number };
    try { payload = this.verify(context.token ?? ''); } catch { throw new ServerError(401, 'unauthorized'); }
    const user = await DuelRoom.deps.prisma.user.findUnique({ where: { id: payload.sub }, select: { id: true, displayName: true, character: true, tokenVersion: true, flagged: true } });
    if (!user || (payload.tv ?? 0) !== user.tokenVersion) throw new ServerError(401, 'unauthorized');
    if (user.flagged) throw new ServerError(403, 'flagged');
    if ([...this.seats.values()].some((s) => s.userId === user.id)) throw new ServerError(409, 'already_in_room');
    return { userId: user.id, name: user.displayName, character: user.character };
  }

  onJoin(client: Client, _options: unknown, auth: AuthData): void {
    const p = new DuelPlayer();
    p.name = auth.name;
    p.character = auth.character;
    this.state.players.set(client.sessionId, p);
    this.seats.set(client.sessionId, { userId: auth.userId, sim: null, queue: [] });
    if (this.state.phase === 'waiting' && this.seats.size >= DUEL_MIN_PLAYERS) this.beginCountdown();
    if (this.seats.size >= DUEL_MAX_PLAYERS) void this.lock();
  }

  onLeave(client: Client): void {
    const seat = this.seats.get(client.sessionId);
    const p = this.state.players.get(client.sessionId);
    if (!seat || !p) return;
    if (this.state.phase === 'waiting' || this.state.phase === 'countdown') {
      this.seats.delete(client.sessionId);
      this.state.players.delete(client.sessionId);
      if (this.state.phase === 'countdown' && this.seats.size < DUEL_MIN_PLAYERS) {
        this.countdown?.clear();
        this.state.phase = 'waiting';
        void this.unlock();
      }
      return;
    }
    // Leaving mid-show ends that player's run with the score they have.
    p.connected = false;
    if (seat.sim && seat.sim.state === 'active') seat.sim.quit(Math.max(seat.sim.lastT, this.elapsed() - DUEL_TAP_LAG_MS));
    p.alive = false;
  }

  onDispose(): void {
    metrics.duelRooms.dec();
  }

  private elapsed(): number { return Date.now() - this.startedAt; }

  private beginCountdown(): void {
    this.state.phase = 'countdown';
    this.state.countdownMs = DUEL_COUNTDOWN_MS;
    this.countdown = this.clock.setTimeout(() => this.start(), DUEL_COUNTDOWN_MS);
  }

  private start(): void {
    void this.lock();
    this.state.phase = 'playing';
    this.startedAt = Date.now();
    for (const seat of this.seats.values()) seat.sim = new RunSim(this.state.seed, DEFAULT_PARAMS);
    this.syncPlayers();
    this.broadcast('start', { seed: this.state.seed });
  }

  private tick(): void {
    if (this.state.phase !== 'playing') return;
    const now = this.elapsed();
    for (const seat of this.seats.values()) {
      const sim = seat.sim!;
      for (const t of seat.queue.splice(0)) {
        // Bounded by the server clock: no taps from the future, none hoarded from the past.
        if (t > now + DUEL_TAP_LEAD_MS || t < now - DUEL_TAP_LAG_MS || t < sim.lastT) { metrics.duelRejectedTaps.inc(); continue; }
        sim.advance(t);
        sim.tap(t);
      }
      // Timeouts are resolved with the same lag window, so a tap still in flight is never pre-empted.
      sim.advance(now - DUEL_TAP_LAG_MS);
    }
    this.syncPlayers();
    const allDone = [...this.seats.values()].every((s) => s.sim!.state === 'dead');
    if (allDone || now > DUEL_MAX_MS + DUEL_TAP_LAG_MS) void this.finish();
  }

  private syncPlayers(): void {
    for (const [id, seat] of this.seats) {
      const p = this.state.players.get(id);
      const sim = seat.sim;
      if (!p || !sim) continue;
      p.score = sim.score; p.fits = sim.fits; p.lives = sim.lives; p.combo = sim.combo; p.alive = sim.state === 'active';
    }
  }

  private async finish(): Promise<void> {
    if (this.state.phase === 'finished') return;
    this.state.phase = 'finished';
    for (const seat of this.seats.values()) if (seat.sim!.state === 'active') seat.sim!.quit(Math.max(seat.sim!.lastT, this.elapsed()));
    this.syncPlayers();
    const ranking = [...this.seats.entries()]
      .map(([id, s]) => ({ sessionId: id, userId: s.userId, name: this.state.players.get(id)!.name, score: s.sim!.score, fits: s.sim!.fits }))
      .sort((a, b) => b.score - a.score || b.fits - a.fits);
    const top = ranking[0];
    const second = ranking[1];
    const winner = top && second && (top.score > second.score || top.fits > second.fits) ? top : null;
    this.state.winner = winner?.sessionId ?? '';
    let rewarded = 0;
    try {
      const { prisma, config } = DuelRoom.deps;
      const paused = await rewardsPaused(prisma, config.DAILY_CREDIT_LIABILITY_LIMIT);
      await prisma.$transaction(async (tx) => {
        const match = await tx.duelMatch.create({ data: { roomId: this.roomId, winnerId: winner?.userId ?? null, players: ranking.map(({ userId, name, score, fits }) => ({ userId, name, score, fits })) as Prisma.InputJsonValue } });
        if (winner && !paused) {
          const since = new Date(Date.now() - 86_400_000);
          const wins = await tx.duelMatch.count({ where: { winnerId: winner.userId, createdAt: { gte: since } } });
          // Cap rewarded wins per day so duels between one's own accounts cannot be farmed.
          if (wins <= DUEL_REWARDED_WINS_PER_DAY) rewarded = await grant(tx, winner.userId, 'credits', DUEL_WIN_CREDITS, 'duel', match.id);
        }
        await tx.event.create({ data: { userId: winner?.userId ?? null, type: 'duel_finish', data: { roomId: this.roomId, players: ranking.length, rewarded } } });
      });
    } catch (err) {
      console.error('duel persist failed', err);
    }
    metrics.duelMatches.inc();
    this.broadcast('result', { ranking: ranking.map(({ sessionId, name, score, fits }) => ({ sessionId, name, score, fits })), winner: winner?.sessionId ?? null, reward: rewarded });
    this.clock.setTimeout(() => void this.disconnect(), 30_000);
  }
}
