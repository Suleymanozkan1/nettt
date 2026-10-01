import { createRng } from './rng';
import {
  BOSS_DRIFT_PERIOD, BOSS_DRIFT_X, COMBO_SCORE_CAP, GOOD_ERROR, HOLE_SWING_LIMIT, MAX_INPUTS, MAX_REVIVES,
  MAX_RUN_MS, MAX_SWING, REVIVE_DELAY_MS, ROUND_DELAY_MS, ROUNDS_PER_LEVEL, SCALE_TOLERANCE, SHAPES, X_TOLERANCE, isBossLevel,
  levelForRounds, omegaForLevel, perfectError, roundTimeMs, shadowScale, shadowX, startLives, wobbleForLevel,
  type RunParams, type ShapeId,
} from './rules';

export type RunInput = { t: number; k: 'tap' | 'revive' | 'quit' };
export type Grade = 'perfect' | 'good' | 'miss';

export type SimEvent =
  | { type: 'fit'; grade: Grade; error: number; points: number; combo: number; score: number; lives: number; level: number; levelUp: boolean; bossStart: boolean; view: RoundView }
  | { type: 'timeout'; lives: number; level: number; levelUp: boolean; bossStart: boolean }
  | { type: 'dead'; t: number }
  | { type: 'revive'; lives: number };

export interface RoundView {
  index: number;
  shape: ShapeId;
  theta: number;
  shadowX: number;
  shadowScale: number;
  holeX: number;
  holeScale: number;
  /** True while the round accepts a tap. */
  ready: boolean;
  /** 1 → 0 over the round. */
  timeLeft: number;
  boss: boolean;
}

export interface RunSummary {
  score: number;
  fits: number;
  perfects: number;
  misses: number;
  maxCombo: number;
  level: number;
  bossCleared: number;
  revives: number;
  durationMs: number;
}

interface Round {
  index: number; start: number; deadline: number; level: number; shape: ShapeId;
  phase: number; phase2: number; driftPhase: number; holeX: number; holeScale: number;
}

/**
 * Deterministic run simulation. The client drives it live (rendering from `view`) and the server replays
 * recorded inputs through the same class, so a client cannot claim a score its inputs do not produce.
 */
export class RunSim {
  state: 'active' | 'dead' = 'active';
  score = 0;
  fits = 0;
  perfects = 0;
  misses = 0;
  combo = 0;
  maxCombo = 0;
  bossCleared = 0;
  revives = 0;
  lives: number;
  lastT = 0;
  deathT = 0;
  /** Fit error of every perfect/good tap (input to the server's superhuman-precision check). */
  readonly fitErrors: number[] = [];
  private rounds = 0;
  private round: Round;
  private readonly rng: () => number;

  constructor(seed: number, readonly params: RunParams) {
    this.rng = createRng(seed);
    this.lives = startLives(params);
    this.rounds = (Math.max(1, params.startAct ?? 1) - 1) * ROUNDS_PER_LEVEL;
    this.round = this.makeRound(0);
  }

  get level(): number { return levelForRounds(this.rounds); }

  private makeRound(start: number): Round {
    const level = this.level;
    const holeTheta = (this.rng() * 2 - 1) * HOLE_SWING_LIMIT * MAX_SWING;
    const shape = SHAPES[Math.floor(this.rng() * SHAPES.length)]!;
    const phase = this.rng() * Math.PI * 2;
    const phase2 = this.rng() * Math.PI * 2;
    const driftPhase = this.rng() * Math.PI * 2;
    return {
      index: this.rounds, start, deadline: start + roundTimeMs(level), level, shape, phase, phase2, driftPhase,
      holeX: shadowX(holeTheta), holeScale: shadowScale(holeTheta),
    };
  }

  private thetaAt(t: number): number {
    const r = this.round;
    const dt = Math.max(0, t - r.start);
    const w = omegaForLevel(r.level);
    const b = wobbleForLevel(r.level);
    return MAX_SWING * ((1 - b) * Math.sin(w * dt + r.phase) + b * Math.sin(2.3 * w * dt + r.phase2));
  }

  private holeXAt(t: number): number {
    const r = this.round;
    if (!isBossLevel(r.level)) return r.holeX;
    return r.holeX + BOSS_DRIFT_X * Math.sin(Math.max(0, t - r.start) / BOSS_DRIFT_PERIOD + r.driftPhase);
  }

  view(t: number): RoundView {
    const r = this.round;
    const theta = this.thetaAt(t);
    return {
      index: r.index, shape: r.shape, theta, shadowX: shadowX(theta), shadowScale: shadowScale(theta),
      holeX: this.holeXAt(t), holeScale: r.holeScale,
      ready: this.state === 'active' && t >= r.start && t <= r.deadline,
      timeLeft: Math.min(1, Math.max(0, (r.deadline - t) / (r.deadline - r.start))),
      boss: isBossLevel(r.level),
    };
  }

  /** Resolves round timeouts up to time t. Must be called before input at t (the client calls it every frame). */
  advance(t: number): SimEvent[] {
    const events: SimEvent[] = [];
    while (this.state === 'active' && t > this.round.deadline) {
      const at = this.round.deadline;
      this.lastT = Math.max(this.lastT, at);
      this.misses += 1;
      this.combo = 0;
      this.lives -= 1;
      const ev = this.completeRound(at);
      events.push({ type: 'timeout', lives: this.lives, ...ev });
      if (this.lives <= 0) events.push({ type: 'dead', t: at });
    }
    return events;
  }

  private completeRound(t: number): { level: number; levelUp: boolean; bossStart: boolean } {
    const before = this.level;
    this.rounds += 1;
    const level = this.level;
    const levelUp = level > before;
    if (levelUp && isBossLevel(before) && this.lives > 0) this.bossCleared += 1;
    if (this.lives <= 0) {
      this.state = 'dead';
      this.deathT = t;
    } else {
      this.round = this.makeRound(t + ROUND_DELAY_MS);
    }
    return { level, levelUp, bossStart: levelUp && isBossLevel(level) };
  }

  canTap(t: number): boolean {
    return this.state === 'active' && t >= this.round.start && t <= this.round.deadline && t >= this.lastT;
  }

  tap(t: number): SimEvent | null {
    if (!this.canTap(t)) return null;
    this.lastT = t;
    const v = this.view(t);
    const dx = (v.shadowX - v.holeX) / X_TOLERANCE;
    const ds = (v.shadowScale - v.holeScale) / SCALE_TOLERANCE;
    const error = Math.sqrt(dx * dx + ds * ds);
    let grade: Grade;
    let points = 0;
    if (error < GOOD_ERROR) this.fitErrors.push(error);
    if (error <= perfectError(this.round.level, this.params)) {
      grade = 'perfect';
      this.combo += 1;
      this.perfects += 1;
      this.fits += 1;
      points = 3 + Math.min(this.combo, COMBO_SCORE_CAP);
    } else if (error < GOOD_ERROR) {
      grade = 'good';
      this.combo = 0;
      this.fits += 1;
      points = error < 0.5 ? 2 : 1;
    } else {
      grade = 'miss';
      this.combo = 0;
      this.misses += 1;
      this.lives -= 1;
    }
    this.score += points;
    this.maxCombo = Math.max(this.maxCombo, this.combo);
    const ev = this.completeRound(t);
    return { type: 'fit', grade, error, points, combo: this.combo, score: this.score, lives: this.lives, view: v, ...ev };
  }

  canRevive(t: number): boolean {
    return this.state === 'dead' && this.revives < MAX_REVIVES && t >= this.deathT && t >= this.lastT;
  }

  revive(t: number): SimEvent | null {
    if (!this.canRevive(t)) return null;
    this.lastT = t;
    this.revives += 1;
    this.lives = 1;
    this.state = 'active';
    this.round = this.makeRound(t + REVIVE_DELAY_MS);
    return { type: 'revive', lives: this.lives };
  }

  /** Player ends the run voluntarily (pause → end show); rewards are still banked. */
  quit(t: number): boolean {
    if (this.state !== 'active' || t < this.lastT) return false;
    this.lastT = t;
    this.state = 'dead';
    this.deathT = t;
    return true;
  }

  summary(): RunSummary {
    return {
      score: this.score, fits: this.fits, perfects: this.perfects, misses: this.misses, maxCombo: this.maxCombo,
      level: this.level, bossCleared: this.bossCleared, revives: this.revives, durationMs: Math.max(this.lastT, this.deathT),
    };
  }
}

export type ReplayResult = { ok: true; summary: RunSummary; fitErrors: number[] } | { ok: false; reason: string };

export const SUPERHUMAN_MIN_FITS = 25;
export const SUPERHUMAN_PERFECT_RATIO = 0.95;
export const SUPERHUMAN_MEDIAN_ERROR = 0.02;

/**
 * Human taps jitter by tens of milliseconds, so even experts land perfect fits with a spread of errors.
 * Many fits that are almost all perfect *and* whose median error is near zero (≈ ±3 ms) indicate a bot
 * driving the open simulation. Used to flag accounts for review — never to change a verified score.
 */
export function looksSuperhuman(s: Pick<RunSummary, 'fits' | 'perfects'>, fitErrors: number[]): boolean {
  if (s.fits < SUPERHUMAN_MIN_FITS || s.perfects / s.fits < SUPERHUMAN_PERFECT_RATIO) return false;
  const sorted = [...fitErrors].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)] ?? 1;
  return median < SUPERHUMAN_MEDIAN_ERROR;
}

/**
 * Server-side authoritative replay. Any input the live client could not have produced rejects the run.
 * After the last input the remaining rounds time out until the run ends, exactly as on the client.
 */
export function replayRun(seed: number, params: RunParams, inputs: RunInput[]): ReplayResult {
  if (inputs.length > MAX_INPUTS) return { ok: false, reason: 'too_many_inputs' };
  const sim = new RunSim(seed, params);
  let prev = -1;
  for (const input of inputs) {
    if (!Number.isInteger(input.t) || input.t < 0 || input.t > MAX_RUN_MS) return { ok: false, reason: 'bad_timestamp' };
    if (input.t < prev) return { ok: false, reason: 'non_monotonic' };
    prev = input.t;
    sim.advance(input.t);
    const ok = input.k === 'tap' ? sim.tap(input.t) !== null
      : input.k === 'revive' ? sim.revive(input.t) !== null
      : sim.quit(input.t);
    if (!ok) return { ok: false, reason: `impossible_${input.k}` };
  }
  sim.advance(MAX_RUN_MS * 2);
  if (sim.state !== 'dead') return { ok: false, reason: 'run_not_over' };
  return { ok: true, summary: sim.summary(), fitErrors: sim.fitErrors };
}
