import { describe, expect, it } from 'vitest';
import { RunSim, DEFAULT_PARAMS, replayRun, looksSuperhuman, createRng, type RunInput } from '../src';

/** Plays `n` rounds aiming at the best moment, with human-like timing jitter of ±jitterMs. */
function play(seed: number, n: number, jitterMs: number): RunInput[] {
  const sim = new RunSim(seed, { ...DEFAULT_PARAMS, toleranceLevel: 3, encoreLevel: 2 });
  const rng = createRng(seed ^ 0x5eed);
  const inputs: RunInput[] = [];
  let t = 0;
  for (let i = 0; i < n && sim.state === 'active'; i++) {
    while (!sim.canTap(t)) t++;
    let best = t; let err = Infinity;
    for (let x = t; sim.canTap(x); x++) {
      const v = sim.view(x); const e = Math.hypot((v.shadowX - v.holeX) / 60, (v.shadowScale - v.holeScale) / 0.35);
      if (e < err) { err = e; best = x; }
    }
    t = Math.max(t, best + Math.round((rng() * 2 - 1) * jitterMs));
    sim.tap(t); inputs.push({ t, k: 'tap' });
  }
  return inputs;
}

describe('superhuman precision detector', () => {
  it('flags a frame-perfect bot', () => {
    const params = { ...DEFAULT_PARAMS, toleranceLevel: 3, encoreLevel: 2 };
    const r = replayRun(7, params, play(7, 30, 0));
    expect(r.ok && looksSuperhuman(r.summary, r.fitErrors)).toBe(true);
  });
  it('does not flag skilled humans (timing jitter) or short runs', () => {
    const params = { ...DEFAULT_PARAMS, toleranceLevel: 3, encoreLevel: 2 };
    for (const seed of [1, 2, 3, 4, 5]) {
      const r = replayRun(seed, params, play(seed, 40, 12));
      expect(r.ok && looksSuperhuman(r.summary, r.fitErrors)).toBe(false);
    }
    const short = replayRun(7, params, play(7, 10, 0));
    expect(short.ok && looksSuperhuman(short.summary, short.fitErrors)).toBe(false);
  });
});
