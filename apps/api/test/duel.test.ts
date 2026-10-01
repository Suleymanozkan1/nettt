import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import type { Server } from 'colyseus';
import { Client, type Room } from 'colyseus.js';
import { DEFAULT_PARAMS, DUEL_COUNTDOWN_MS, DUEL_WIN_CREDITS, RunSim } from '@stage/shared';
import { startRealtime } from '../src/realtime-server';
import { loadConfig } from '../src/config';
import { guest, makeApp, prisma, resetDb } from './helpers';

const PORT = 25670;
let app: FastifyInstance;
let rt: Server;

beforeAll(async () => {
  app = await makeApp();
  const config = loadConfig({ ...process.env, JWT_SECRET: 'test-secret-test-secret-test-secret-1234' });
  rt = await startRealtime(PORT, { prisma, config });
});
afterAll(async () => { await rt.gracefullyShutdown(false); await app.close(); });
beforeEach(async () => { await resetDb(); await prisma.$executeRawUnsafe('TRUNCATE "DuelMatch" CASCADE'); });

async function join(token: string): Promise<Room> {
  const c = new Client(`ws://localhost:${PORT}`);
  c.auth.token = token;
  return c.joinOrCreate('duel');
}
const waitFor = async (pred: () => boolean, ms = 15000) => {
  const end = Date.now() + ms;
  while (!pred()) { if (Date.now() > end) throw new Error('timeout'); await new Promise((r) => setTimeout(r, 25)); }
};

/** Best tap time in the current round as seen by a local copy of the simulation. */
function bestTap(sim: RunSim, from: number): number {
  let t = from; while (!sim.canTap(t)) t++;
  let best = t; let err = Infinity;
  for (let x = t; sim.canTap(x); x++) {
    const v = sim.view(x); const e = Math.hypot((v.shadowX - v.holeX) / 60, (v.shadowScale - v.holeScale) / 0.35);
    if (e < err) { err = e; best = x; }
  }
  return best;
}

describe('live duel (Colyseus)', () => {
  it('rejects unauthenticated joins', async () => {
    await expect(join('not-a-token')).rejects.toThrow();
  });

  it('server scores both players, ignores forged future taps, rewards the winner once', async () => {
    const a = await guest(app); const b = await guest(app);
    const ra = await join(a.token);
    const rb = await join(b.token);
    expect(rb.roomId).toBe(ra.roomId);
    let seed = -1;
    ra.onMessage('start', (m: { seed: number }) => { seed = m.seed; });
    rb.onMessage('start', () => undefined);
    let result: { winner: string | null; reward: number; ranking: { sessionId: string; score: number }[] } | null = null;
    ra.onMessage('result', (m) => { result = m; });
    rb.onMessage('result', () => undefined);
    await waitFor(() => seed >= 0, DUEL_COUNTDOWN_MS + 3000);
    const t0 = Date.now();

    // Player B forges a tap far in the future: the server must ignore it.
    rb.send('tap', { t: 999_999 });
    // Player A plays three perfect rounds in real time, stamping taps with its local clock.
    const sim = new RunSim(seed, DEFAULT_PARAMS);
    let t = 0;
    for (let i = 0; i < 3; i++) {
      t = bestTap(sim, t);
      await new Promise((r) => setTimeout(r, Math.max(0, t - (Date.now() - t0))));
      sim.tap(t);
      ra.send('tap', { t });
    }
    await waitFor(() => (ra.state as { players: Map<string, { fits: number }> }).players.get(ra.sessionId)?.fits === 3, 5000);
    expect((rb.state as { players: Map<string, { fits: number }> }).players.get(rb.sessionId)?.fits).toBe(0);

    // Both leave the game to play out: A leaves (keeps score), B times out → duel ends.
    await rb.leave();
    await waitFor(() => result !== null, 25000);
    expect(result!.winner).toBe(ra.sessionId);
    expect(result!.reward).toBe(DUEL_WIN_CREDITS);
    expect(result!.ranking[0]!.score).toBe(sim.score);
    const match = await prisma.duelMatch.findFirstOrThrow({ where: { roomId: ra.roomId } });
    expect(match.winnerId).toBe(a.id);
    expect((await prisma.user.findUniqueOrThrow({ where: { id: a.id } })).credits).toBe(DUEL_WIN_CREDITS);
    await ra.leave();
  }, 60000);
});
