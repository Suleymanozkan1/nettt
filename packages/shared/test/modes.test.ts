import { describe, expect, it } from 'vitest';
import {
  RunSim, DEFAULT_PARAMS, ROUNDS_PER_LEVEL, unlockedStartActs, isoWeekKey, challengeSeed, computeRunRewards,
  RUN_CREDIT_CAP, roundTimeMs, type RunSummary,
} from '../src';

const run = (o: Partial<RunSummary> = {}): RunSummary => ({ score: 40, fits: 10, perfects: 4, misses: 3, maxCombo: 3, level: 2, bossCleared: 1, revives: 0, durationMs: 1, ...o });

describe('level select (start act)', () => {
  it('starts the simulation at the chosen act with its difficulty', () => {
    const sim = new RunSim(1, { ...DEFAULT_PARAMS, startAct: 6 });
    expect(sim.level).toBe(6);
    expect(sim.view(0).timeLeft).toBe(1);
    sim.advance(roundTimeMs(6) + 1);
    expect(sim.misses).toBe(1); // a 6th-act round is shorter than a 1st-act round
    expect(roundTimeMs(6)).toBeLessThan(roundTimeMs(1));
    expect(new RunSim(1, { toleranceLevel: 0, encoreLevel: 0 }).level).toBe(1); // legacy params
    expect(ROUNDS_PER_LEVEL).toBe(10);
  });
  it('unlocks acts 6 and 11 only after reaching them', () => {
    expect(unlockedStartActs(1)).toEqual([1]);
    expect(unlockedStartActs(7)).toEqual([1, 6]);
    expect(unlockedStartActs(30)).toEqual([1, 6, 11]);
  });
});

describe('weekly challenge', () => {
  it('uses the Monday of the ISO week and one seed for everyone that week', () => {
    expect(isoWeekKey(new Date('2026-10-01T12:00:00Z'))).toBe('2026-09-28'); // Thursday → Monday
    expect(isoWeekKey(new Date('2026-10-04T23:59:00Z'))).toBe('2026-09-28'); // Sunday
    expect(isoWeekKey(new Date('2026-10-05T00:00:00Z'))).toBe('2026-10-05');
    expect(challengeSeed('2026-09-28')).toBe(challengeSeed('2026-09-28'));
    expect(challengeSeed('2026-09-28')).not.toBe(challengeSeed('2026-10-05'));
    expect(challengeSeed('2026-09-28')).toBeGreaterThanOrEqual(0);
  });
});

describe('special event multipliers', () => {
  it('boost fans/credits, clamp to ×1–×3, never touch gems, respect the cap', () => {
    expect(computeRunRewards(run())).toEqual({ fans: 43, credits: 40, gems: 1 });
    expect(computeRunRewards(run(), { fans: 2, credits: 1.5 })).toEqual({ fans: 86, credits: 60, gems: 1 });
    expect(computeRunRewards(run(), { fans: 10, credits: 0.1 })).toEqual({ fans: 129, credits: 40, gems: 1 });
    expect(computeRunRewards(run({ score: 5000 }), { fans: 1, credits: 3 }).credits).toBe(RUN_CREDIT_CAP);
  });
});
