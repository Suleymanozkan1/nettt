import { describe, expect, it } from 'vitest';
import {
  RunSim, replayRun, DEFAULT_PARAMS, ROUND_DELAY_MS, ROUNDS_PER_LEVEL, levelForRounds, isBossLevel, omegaForLevel,
  MAX_SPEED_MULT, BASE_PERIOD_MS, createRng, roundTimeMs, START_LIVES, shadowX, shadowScale, MAX_SWING,
  WALL_CENTER_X, type RunInput,
} from '../src';

/** Error of the fit if the player tapped at t. */
function fitError(sim: RunSim, t: number): number {
  const v = sim.view(t);
  return Math.hypot((v.shadowX - v.holeX) / 60, (v.shadowScale - v.holeScale) / 0.35);
}

/** First tappable time ≥ from with the smallest error (a skilled perfect tap). */
function bestTime(sim: RunSim, from: number): number {
  let best = -1; let bestErr = Infinity;
  for (let t = from; t < from + 6000; t++) {
    if (!sim.canTap(t)) { if (best >= 0) break; continue; }
    const e = fitError(sim, t);
    if (e < bestErr) { bestErr = e; best = t; }
  }
  return best;
}

/** First tappable time whose error is clearly a miss. */
function missTime(sim: RunSim, from: number): number {
  for (let t = from; t < from + 6000; t++) if (sim.canTap(t) && fitError(sim, t) > 1.5) return t;
  throw new Error('no miss time');
}

function nextStart(sim: RunSim, from: number): number {
  let t = from;
  while (!sim.canTap(t)) t++;
  return t;
}

describe('rng', () => {
  it('is deterministic per seed', () => {
    const a = createRng(42); const b = createRng(42); const c = createRng(43);
    const sa = [a(), a(), a()];
    expect(sa).toEqual([b(), b(), b()]);
    expect(sa).not.toEqual([c(), c(), c()]);
  });
});

describe('shadow geometry', () => {
  it('shadow slides and grows as the lamp swings out', () => {
    expect(shadowX(0)).toBe(WALL_CENTER_X);
    expect(shadowScale(0)).toBe(1);
    expect(shadowX(MAX_SWING)).toBeCloseTo(WALL_CENTER_X - 150);
    expect(shadowScale(MAX_SWING)).toBeCloseTo(1.9);
    expect(shadowScale(-0.5)).toBeCloseTo(shadowScale(0.5));
  });
});

describe('RunSim', () => {
  it('perfect fits build combo and score bonus', () => {
    const sim = new RunSim(7, DEFAULT_PARAMS);
    let t = 0;
    const pts: number[] = [];
    for (let i = 0; i < 4; i++) {
      t = bestTime(sim, nextStart(sim, t));
      const ev = sim.tap(t);
      expect(ev?.type).toBe('fit');
      if (ev?.type === 'fit') { expect(ev.grade).toBe('perfect'); pts.push(ev.points); }
    }
    expect(pts).toEqual([4, 5, 6, 7]);
    expect(sim.combo).toBe(4);
    expect(sim.lives).toBe(START_LIVES);
  });

  it('a bad fit is a miss and costs a life; combo resets', () => {
    const sim = new RunSim(11, DEFAULT_PARAMS);
    const t = missTime(sim, 0);
    const ev = sim.tap(t);
    expect(ev).toMatchObject({ type: 'fit', grade: 'miss', points: 0, lives: START_LIVES - 1, combo: 0 });
  });

  it('rounds time out, costing lives until the show ends; one revive allowed', () => {
    const sim = new RunSim(3, DEFAULT_PARAMS);
    const evs = sim.advance(60_000);
    expect(evs.filter((e) => e.type === 'timeout')).toHaveLength(START_LIVES);
    expect(evs.at(-1)?.type).toBe('dead');
    expect(sim.state).toBe('dead');
    const dt = sim.deathT;
    expect(dt).toBe(roundTimeMs(1) * 3 + ROUND_DELAY_MS * 2);
    expect(sim.revive(dt - 1)).toBeNull();
    expect(sim.revive(dt + 100)).toMatchObject({ type: 'revive', lives: 1 });
    sim.advance(dt + 100_000);
    expect(sim.state).toBe('dead');
    expect(sim.revive(sim.deathT + 10)).toBeNull();
  });

  it('ignores taps between rounds', () => {
    const sim = new RunSim(5, DEFAULT_PARAMS);
    const t = bestTime(sim, 0);
    sim.tap(t);
    expect(sim.tap(t + ROUND_DELAY_MS - 1)).toBeNull();
    expect(sim.canTap(t + ROUND_DELAY_MS)).toBe(true);
  });

  it('levels up every ROUNDS_PER_LEVEL rounds and counts cleared bosses', () => {
    const sim = new RunSim(99, { toleranceLevel: 3, encoreLevel: 2 });
    let t = 0;
    for (let i = 0; i < ROUNDS_PER_LEVEL * 5; i++) {
      t = bestTime(sim, nextStart(sim, t));
      sim.tap(t);
    }
    expect(sim.state).toBe('active');
    expect(sim.level).toBe(6);
    expect(sim.bossCleared).toBe(1);
  });
});

describe('difficulty curve', () => {
  it('speeds up per level, caps, shortens rounds', () => {
    expect(levelForRounds(0)).toBe(1);
    expect(levelForRounds(10)).toBe(2);
    expect(omegaForLevel(2)).toBeGreaterThan(omegaForLevel(1));
    expect(omegaForLevel(500)).toBeCloseTo((2 * Math.PI / BASE_PERIOD_MS) * MAX_SPEED_MULT);
    expect(roundTimeMs(20)).toBeLessThan(roundTimeMs(1));
    expect(roundTimeMs(500)).toBe(2600);
    expect(isBossLevel(5)).toBe(true);
    expect(isBossLevel(4)).toBe(false);
  });
});

describe('replayRun (server authority)', () => {
  function record(seed: number, perfect: number): RunInput[] {
    const sim = new RunSim(seed, DEFAULT_PARAMS);
    const inputs: RunInput[] = [];
    let t = 0;
    for (let i = 0; i < perfect; i++) {
      t = bestTime(sim, nextStart(sim, t));
      sim.tap(t); inputs.push({ t, k: 'tap' });
    }
    return inputs;
  }

  it('reproduces the client result and resolves trailing timeouts', () => {
    const inputs = record(1234, 6);
    const r = replayRun(1234, DEFAULT_PARAMS, inputs);
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.summary).toMatchObject({ fits: 6, perfects: 6, misses: START_LIVES, maxCombo: 6 });
      expect(r.summary.durationMs).toBeGreaterThan(inputs.at(-1)!.t);
    }
  });

  it('rejects inputs replayed against a different seed or tampered timing', () => {
    const inputs = record(1234, 6);
    const other = replayRun(9999, DEFAULT_PARAMS, inputs);
    if (other.ok) expect(other.summary.perfects).toBeLessThan(6);
    const spam = inputs.map((i, idx) => ({ ...i, t: 10 + idx }));
    expect(replayRun(1234, DEFAULT_PARAMS, spam)).toEqual({ ok: false, reason: 'impossible_tap' });
    expect(replayRun(1234, DEFAULT_PARAMS, [...inputs].reverse()).ok).toBe(false);
    expect(replayRun(1234, DEFAULT_PARAMS, [{ t: 1.5, k: 'tap' }])).toEqual({ ok: false, reason: 'bad_timestamp' });
    expect(replayRun(1234, DEFAULT_PARAMS, [{ t: 100, k: 'revive' }])).toEqual({ ok: false, reason: 'impossible_revive' });
  });

  it('accepts a quit and rejects input after it', () => {
    expect(replayRun(5, DEFAULT_PARAMS, [{ t: 1000, k: 'quit' }])).toEqual({ ok: true, summary: expect.objectContaining({ score: 0, fits: 0, durationMs: 1000 }) });
    expect(replayRun(5, DEFAULT_PARAMS, [{ t: 1000, k: 'quit' }, { t: 2000, k: 'tap' }]).ok).toBe(false);
  });
});
