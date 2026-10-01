import { describe, expect, it } from 'vitest';
import {
  computeRunRewards, playerLevelForFans, fansForPlayerLevel, checkDaily, DAILY_REWARDS, dailyMissions,
  applyRunToMission, CATALOG, UPGRADES, upgradeCost, RUN_CREDIT_CAP, applyDailyPacing, FULL_REWARD_SHOWS_PER_DAY,
  RUN_GEMS_PER_DAY, type RunSummary,
} from '../src';

const run = (o: Partial<RunSummary> = {}): RunSummary => ({ score: 30, fits: 20, perfects: 5, misses: 3, maxCombo: 3, level: 2, bossCleared: 0, revives: 0, durationMs: 60000, ...o });

describe('run rewards', () => {
  it('derives fans/credits/gems from server summary and caps credits', () => {
    expect(computeRunRewards(run())).toEqual({ fans: 30, credits: 6, gems: 0 });
    expect(computeRunRewards(run({ bossCleared: 1, score: 100 }))).toEqual({ fans: 55, credits: 30, gems: 1 });
    expect(computeRunRewards(run({ score: 100000 })).credits).toBe(RUN_CREDIT_CAP);
  });
});

describe('daily pacing ("tired audience")', () => {
  it('pays full credits for the first shows of the day, then 25%; fans never reduced; gems capped', () => {
    const r = { fans: 40, credits: 100, gems: 2 };
    expect(applyDailyPacing(r, 0, 0)).toEqual({ fans: 40, credits: 100, gems: 2, tired: false });
    expect(applyDailyPacing(r, FULL_REWARD_SHOWS_PER_DAY - 1, 0).tired).toBe(false);
    expect(applyDailyPacing(r, FULL_REWARD_SHOWS_PER_DAY, 0)).toEqual({ fans: 40, credits: 25, gems: 2, tired: true });
    expect(applyDailyPacing(r, 0, RUN_GEMS_PER_DAY - 1).gems).toBe(1);
    expect(applyDailyPacing(r, 0, RUN_GEMS_PER_DAY + 2).gems).toBe(0);
  });
});

describe('player level', () => {
  it('is monotonic and consistent with thresholds', () => {
    for (let l = 1; l < 30; l++) {
      expect(playerLevelForFans(fansForPlayerLevel(l))).toBe(l);
      expect(playerLevelForFans(fansForPlayerLevel(l + 1) - 1)).toBe(l);
    }
  });
});

describe('daily streak', () => {
  it('advances on consecutive days and blocks double claim', () => {
    const d1 = checkDaily({ streak: 0, lastClaimDay: null, graceUsed: false }, '2026-01-01');
    expect(d1.canClaim && d1.cycleDay).toBe(1);
    const d2 = checkDaily(d1.next, '2026-01-02');
    expect(d2.canClaim && d2.cycleDay).toBe(2);
    expect(checkDaily(d2.next, '2026-01-02').canClaim).toBe(false);
  });
  it('one missed day uses grace, two restart without penalty', () => {
    const s = { streak: 3, lastClaimDay: '2026-01-03', graceUsed: false };
    const g = checkDaily(s, '2026-01-05');
    expect(g.canClaim && g.cycleDay).toBe(4);
    expect(g.next.graceUsed).toBe(true);
    const r = checkDaily(g.next, '2026-01-07');
    expect(r.canClaim && r.cycleDay).toBe(1);
  });
  it('wraps after 7 days', () => {
    const c = checkDaily({ streak: 7, lastClaimDay: '2026-01-07', graceUsed: false }, '2026-01-08');
    expect(c.canClaim && c.cycleDay).toBe(1);
    expect(DAILY_REWARDS).toHaveLength(7);
  });
});

describe('missions', () => {
  it('gives 3 distinct-kind missions per user/day, stable', () => {
    const a = dailyMissions('u1', '2026-01-01');
    expect(a).toHaveLength(3);
    expect(new Set(a.map((m) => m.kind)).size).toBe(3);
    expect(dailyMissions('u1', '2026-01-01')).toEqual(a);
  });
  it('applies run progress by kind', () => {
    const m = { key: 'x', kind: 'score' as const, target: 40, reward: 1, label: '' };
    expect(applyRunToMission(m, 50, run({ score: 30 }))).toBe(50);
    expect(applyRunToMission({ ...m, kind: 'fits' }, 10, run())).toBe(30);
  });
});

describe('catalog', () => {
  it('has unique ids, free defaults and increasing upgrade costs', () => {
    expect(new Set(CATALOG.map((c) => c.id)).size).toBe(CATALOG.length);
    expect(CATALOG.filter((c) => c.price === 0)).toHaveLength(2);
    expect(CATALOG.filter((c) => c.price === 0).map((c) => c.id).sort()).toEqual(['char_fox', 'lamp_candle']);
    for (const u of UPGRADES) for (let i = 1; i < u.costs.length; i++) expect(u.costs[i]!).toBeGreaterThan(u.costs[i - 1]!);
    expect(upgradeCost('tolerance', 3)).toBeNull();
  });
});
