import { describe, expect, it } from 'vitest';
import { CATALOG, RunSim, computeRunRewards, replayRun, type RunInput, type RunParams } from '@stage/shared';
import { createLocalApi, pruneHistory, type KeyValue, type Show } from '../src/lib/local-backend';

function memoryStore(): KeyValue & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return { data, get: async (k) => data.get(k) ?? null, set: async (k, v) => { data.set(k, v); } };
}

/** Plays `n` rounds at the best moment (like a careful player). */
function play(seed: number, params: RunParams, n: number): RunInput[] {
  const sim = new RunSim(seed, params);
  const inputs: RunInput[] = [];
  let t = 0;
  for (let i = 0; i < n && sim.state === 'active'; i++) {
    while (!sim.canTap(t)) t++;
    let best = t; let err = Infinity; let last = t;
    for (let x = t; sim.canTap(x); x++) {
      last = x;
      const v = sim.view(x); const e = Math.hypot((v.shadowX - v.holeX) / 60, (v.shadowScale - v.holeScale) / 0.35);
      if (e < err) { err = e; best = x; }
    }
    t = Math.min(best + 40, last); // slightly late, still inside the round
    sim.tap(t); inputs.push({ t, k: 'tap' });
  }
  return inputs;
}

const DAY = new Date('2026-10-01T12:00:00Z');

describe('local (offline) backend', () => {
  it('pays the same rewards as the server rules, from a replay of the inputs', async () => {
    const api = createLocalApi(memoryStore(), () => DAY);
    await api.init();
    const run = await api.startRun({ startAct: 1 });
    const inputs = play(run.seed, run.params, 12);
    const res = await api.finishRun(run.runId, inputs);
    const replay = replayRun(run.seed, run.params, inputs);
    expect(replay.ok).toBe(true);
    if (!replay.ok) return;
    const expected = computeRunRewards(replay.summary);
    expect(res.verified).toBe(true);
    expect(res.summary).toEqual(replay.summary);
    expect(res.rewards).toEqual({ fans: expected.fans, credits: expected.credits, gems: expected.gems });
    const me = await api.me();
    expect(me.credits).toBe(expected.credits);
    expect(me.fans).toBe(expected.fans);
    expect(me.bestScore).toBe(replay.summary.score);
    expect(me.totalRuns).toBe(1);
    // A run can be submitted only once.
    await expect(api.finishRun(run.runId, inputs)).rejects.toMatchObject({ code: 'run_already_submitted' });
  });

  it('rejects impossible inputs and locked acts', async () => {
    const api = createLocalApi(memoryStore(), () => DAY);
    await expect(api.startRun({ startAct: 6 })).rejects.toMatchObject({ code: 'act_locked' });
    const run = await api.startRun({});
    await expect(api.finishRun(run.runId, [{ t: -5, k: 'tap' }])).rejects.toMatchObject({ code: 'run_rejected' });
  });

  it('daily reward is claimable once per day and persists across restarts', async () => {
    const store = memoryStore();
    const a = createLocalApi(store, () => DAY);
    const r = await a.claimDaily();
    await expect(a.claimDaily()).rejects.toMatchObject({ code: 'already_claimed' });
    const b = createLocalApi(store, () => DAY); // app restarted
    expect((await b.me()).credits).toBe(r.reward.credits);
    expect((await b.daily()).canClaim).toBe(false);
    const tomorrow = createLocalApi(store, () => new Date('2026-10-02T09:00:00Z'));
    expect((await tomorrow.daily()).canClaim).toBe(true);
  });

  it('shop: balance checks, purchase, loadout ownership, upgrades', async () => {
    const store = memoryStore();
    const api = createLocalApi(store, () => DAY);
    await api.updateSettings({}); // first save
    const item = CATALOG.find((c) => c.price > 0 && c.currency === 'credits')!;
    await expect(api.buy(item.id)).rejects.toMatchObject({ code: 'insufficient_funds' });
    await expect(api.loadout({ [item.kind]: item.id })).rejects.toMatchObject({ code: 'not_owned' });
    const raw = JSON.parse(store.data.get('stage.local.v1')!);
    store.data.set('stage.local.v1', JSON.stringify({ ...raw, credits: 100_000 }));
    const rich = createLocalApi(store, () => DAY);
    await rich.buy(item.id);
    await expect(rich.buy(item.id)).rejects.toMatchObject({ code: 'already_owned' });
    expect((await rich.loadout({ [item.kind]: item.id }))[item.kind]).toBe(item.id);
    const up = await rich.upgrade('tolerance');
    expect(up.level).toBe(1);
    const run = await rich.startRun({});
    expect(run.params.toleranceLevel).toBe(1);
    expect((await rich.me()).credits).toBe(100_000 - item.price - 600);
  });

  it('missions progress from shows and pay once', async () => {
    const api = createLocalApi(memoryStore(), () => DAY);
    for (let i = 0; i < 5; i++) {
      const run = await api.startRun({});
      await api.finishRun(run.runId, play(run.seed, run.params, 30));
    }
    const { missions } = await api.missions();
    const done = missions.find((m) => m.completed);
    expect(done).toBeDefined();
    const before = (await api.me()).credits;
    await api.claimMission(done!.key);
    expect((await api.me()).credits).toBe(before + done!.reward);
    await expect(api.claimMission(done!.key)).rejects.toMatchObject({ code: 'mission_not_claimable' });
  });

  it('weekly challenge uses the shared weekly seed and caps attempts', async () => {
    const api = createLocalApi(memoryStore(), () => DAY);
    const seeds = new Set<number>();
    for (let i = 0; i < 5; i++) seeds.add((await api.startRun({ mode: 'challenge' })).seed);
    expect(seeds.size).toBe(1);
    await expect(api.startRun({ mode: 'challenge' })).rejects.toMatchObject({ code: 'challenge_attempts_used' });
  });

  it('record history: old high scores never crowd out this week\'s boards', () => {
    const old: Show[] = Array.from({ length: 60 }, (_, i) => ({ score: 1000 + i, at: '2026-01-05', mode: 'normal', week: '2026-W02' }));
    const kept = pruneHistory([...old, { score: 3, at: '2026-10-01', mode: 'normal', week: '2026-W40' }, { score: 2, at: '2026-10-01', mode: 'challenge', week: '2026-W40' }]);
    expect(kept.filter((h) => h.week === '2026-W40')).toHaveLength(2);
    expect(kept.filter((h) => h.week === '2026-W02')).toHaveLength(10);
    expect(Math.max(...kept.map((h) => h.score))).toBe(1059);
  });

  it('online-only features fail clearly', async () => {
    const api = createLocalApi(memoryStore(), () => DAY);
    await expect(api.login('a@b.co', 'x')).rejects.toMatchObject({ code: 'online_only' });
    expect(api.token()).toBeNull();
  });
});
