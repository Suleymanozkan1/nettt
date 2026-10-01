import {
  CATALOG, CHALLENGE_ATTEMPTS_PER_DAY, DAILY_REWARDS, DEFAULT_CHARACTER, DEFAULT_PARAMS, DEFAULT_SETTINGS, DEFAULT_SKIN,
  OnboardingBody, REVIVE_GEM_COST, UPGRADES, applyDailyPacing, applyRunToMission, challengeSeed, checkDaily,
  computeRunRewards, dailyMissions, fansForPlayerLevel, findItem, findMission, isCumulative, isoWeekKey,
  playerLevelForFans, replayRun, unlockedStartActs, upgradeCost, utcDay,
  type DailyState, type RunInput, type RunParams, type Settings, type UpgradeId,
} from '@stage/shared';
import { ApiError } from './api-error';
import { storage } from './storage';
import type { Api, FinishResult, LeaderboardData, Profile, RunMode } from './api';

/**
 * Offline backend for the standalone build: the same contract as the HTTP API, with the player's progression
 * stored on the device. Rewards use the server's shared rules, computed from a replay of the recorded inputs.
 */
const STATE_KEY = 'stage.local.v1';
const BOARD_SIZE = 10;
const WEEKS_KEPT = 8;

export interface Show { score: number; at: string; mode: RunMode; week: string | null }
interface PendingRun { id: string; seed: number; params: RunParams; mode: RunMode; week: string | null }
export interface LocalState {
  v: 1;
  id: string; displayName: string; onboarded: boolean; tutorialDone: boolean; settings: Settings;
  credits: number; gems: number; fans: number; bestScore: number; totalRuns: number; maxAct: number;
  skin: string; character: string; owned: string[]; upgrades: Partial<Record<UpgradeId, number>>;
  daily: DailyState;
  day: { key: string; shows: number; showGems: number; challenges: number; missions: Record<string, number>; claimed: string[] };
  pending: PendingRun | null;
  history: Show[];
}

export interface KeyValue { get(key: string): Promise<string | null>; set(key: string, value: string): Promise<void> }

const randomId = () => Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, '0')).join('');

function fresh(): LocalState {
  return {
    v: 1, id: `local-${randomId()}`, displayName: `Kuklacı${Math.floor(1000 + Math.random() * 9000)}`, onboarded: false, tutorialDone: false,
    settings: { ...DEFAULT_SETTINGS }, credits: 0, gems: 0, fans: 0, bestScore: 0, totalRuns: 0, maxAct: 1,
    skin: DEFAULT_SKIN, character: DEFAULT_CHARACTER, owned: [], upgrades: {},
    daily: { streak: 0, lastClaimDay: null, graceUsed: false },
    day: { key: '', shows: 0, showGems: 0, challenges: 0, missions: {}, claimed: [] },
    pending: null, history: [],
  };
}

const err = (status: number, code: string) => new ApiError(status, code);

/**
 * Keeps what the record boards can show: the all-time top normal shows, plus the top shows of each board
 * (normal / challenge) for the most recent weeks. Low scores in a new week are never crowded out by old highs.
 */
export function pruneHistory(history: Show[]): Show[] {
  const byScore = [...history].sort((a, b) => b.score - a.score);
  const weeks = [...new Set(history.map((h) => h.week ?? ''))].sort().reverse().slice(0, WEEKS_KEPT);
  const keep = new Set(byScore.filter((h) => h.mode === 'normal').slice(0, BOARD_SIZE));
  for (const week of weeks) {
    for (const mode of ['normal', 'challenge'] as const) {
      for (const h of byScore.filter((x) => x.mode === mode && (x.week ?? '') === week).slice(0, BOARD_SIZE)) keep.add(h);
    }
  }
  return byScore.filter((h) => keep.has(h));
}
const owns = (s: LocalState, id: string) => findItem(id)?.price === 0 || s.owned.includes(id);

export function createLocalApi(store: KeyValue = storage, now: () => Date = () => new Date()): Api {
  let cache: LocalState | null = null;
  let queue: Promise<unknown> = Promise.resolve();

  async function load(): Promise<LocalState> {
    if (!cache) {
      const raw = await store.get(STATE_KEY);
      let parsed: LocalState | null = null;
      try { parsed = raw ? (JSON.parse(raw) as LocalState) : null; } catch { parsed = null; }
      cache = parsed?.v === 1 ? { ...fresh(), ...parsed } : fresh();
    }
    const today = utcDay(now());
    if (cache.day.key !== today) cache.day = { key: today, shows: 0, showGems: 0, challenges: 0, missions: {}, claimed: [] };
    return cache;
  }

  /** Serialised read-modify-write; the state is persisted after every mutation. */
  function tx<T>(fn: (s: LocalState) => T | Promise<T>, write = true): Promise<T> {
    const run = queue.then(async () => {
      const s = await load();
      const out = await fn(s);
      if (write) await store.set(STATE_KEY, JSON.stringify(s));
      return out;
    });
    queue = run.catch(() => undefined);
    return run;
  }
  const read = <T>(fn: (s: LocalState) => T) => tx(fn, false);

  const profile = (s: LocalState): Profile => {
    const level = playerLevelForFans(s.fans);
    return {
      id: s.id, displayName: s.displayName, registered: false, role: 'PLAYER', credits: s.credits, gems: s.gems, fans: s.fans,
      level, levelFloor: fansForPlayerLevel(level), nextLevelFans: fansForPlayerLevel(level + 1), bestScore: s.bestScore,
      totalRuns: s.totalRuns, rank: s.bestScore > 0 ? 1 : null, skin: s.skin, character: s.character, tutorialDone: s.tutorialDone,
      settings: s.settings, reviveCost: REVIVE_GEM_COST, onboarded: s.onboarded, maxAct: s.maxAct, startActs: unlockedStartActs(s.maxAct),
    };
  };

  const spend = (s: LocalState, currency: 'credits' | 'gems', amount: number) => {
    if (s[currency] < amount) throw err(409, 'insufficient_funds');
    s[currency] -= amount;
  };

  const missionsView = (s: LocalState) => dailyMissions(s.id, s.day.key).map((m) => {
    const progress = s.day.missions[m.key] ?? 0;
    return { key: m.key, label: m.label, target: m.target, reward: m.reward, progress: Math.min(progress, m.target), completed: progress >= m.target, claimed: s.day.claimed.includes(m.key) };
  });

  const board = (s: LocalState, period: 'all' | 'weekly' | 'challenge'): LeaderboardData => {
    const week = isoWeekKey(now());
    const shows = s.history.filter((h) => h.score > 0 && (period === 'all' ? h.mode === 'normal'
      : period === 'challenge' ? h.mode === 'challenge' && h.week === week : h.mode === 'normal' && h.week === week));
    const top = [...shows].sort((a, b) => b.score - a.score).slice(0, BOARD_SIZE);
    return {
      period,
      entries: top.map((h, i) => ({ rank: i + 1, name: `${s.displayName} · ${h.at.slice(0, 10)}`, score: h.score, character: s.character, me: true })),
      myRank: top.length ? 1 : null,
      myScore: top[0]?.score ?? 0,
    };
  };

  const onlineOnly = () => Promise.reject(err(400, 'online_only'));

  return {
    init: async () => { await read(() => undefined); },
    setToken: async () => undefined,
    token: () => null,
    logout: async () => undefined,
    me: () => read(profile),
    updateSettings: (b) => tx((s) => {
      const { tutorialDone, displayName, ...rest } = b;
      s.settings = { ...s.settings, ...rest };
      if (tutorialDone !== undefined) s.tutorialDone = tutorialDone;
      if (displayName && OnboardingBody.safeParse({ displayName }).success) s.displayName = displayName;
      return { settings: s.settings };
    }),
    register: onlineOnly,
    login: onlineOnly,
    logoutAll: onlineOnly,
    registerPush: async () => ({ ok: false }), // no server to push from
    onboarding: (displayName) => tx((s) => {
      if (!OnboardingBody.safeParse({ displayName }).success) throw err(400, 'validation_error');
      s.displayName = displayName;
      s.onboarded = true;
      return { displayName };
    }),
    activeEvent: async () => ({ event: null }),
    startRun: (body = {}) => tx((s) => {
      const id = `run-${randomId()}`;
      if (body.mode === 'challenge') {
        if (s.day.challenges >= CHALLENGE_ATTEMPTS_PER_DAY) throw err(429, 'challenge_attempts_used');
        s.day.challenges += 1;
        const week = isoWeekKey(now());
        s.pending = { id, seed: challengeSeed(week), params: DEFAULT_PARAMS, mode: 'challenge', week };
        return { runId: id, seed: s.pending.seed, params: DEFAULT_PARAMS, mode: 'challenge' as const, week, attemptsLeft: CHALLENGE_ATTEMPTS_PER_DAY - s.day.challenges };
      }
      const startAct = body.startAct ?? 1;
      if (!unlockedStartActs(s.maxAct).includes(startAct)) throw err(403, 'act_locked');
      const params: RunParams = { toleranceLevel: s.upgrades.tolerance ?? 0, encoreLevel: s.upgrades.encore ?? 0, startAct };
      s.pending = { id, seed: crypto.getRandomValues(new Uint32Array(1))[0]! >>> 1, params, mode: 'normal', week: null };
      return { runId: id, seed: s.pending.seed, params, mode: 'normal' as const };
    }),
    finishRun: (runId: string, inputs: RunInput[]) => tx((s): FinishResult => {
      const run = s.pending;
      if (!run || run.id !== runId) throw err(409, 'run_already_submitted');
      s.pending = null;
      const replay = replayRun(run.seed, run.params, inputs);
      if (!replay.ok) throw new ApiError(422, 'run_rejected', replay.reason);
      const summary = replay.summary;
      if (summary.revives > 0) spend(s, 'gems', REVIVE_GEM_COST * summary.revives);
      const rewards = applyDailyPacing(computeRunRewards(summary), s.day.shows, s.day.showGems);
      s.day.shows += 1;
      s.day.showGems += rewards.gems;
      s.fans += rewards.fans;
      s.credits += rewards.credits;
      s.gems += rewards.gems;
      s.totalRuns += 1;
      s.maxAct = Math.max(s.maxAct, summary.level);
      const newBest = run.mode === 'normal' && summary.score > s.bestScore;
      if (newBest) s.bestScore = summary.score;
      s.history = pruneHistory([...s.history, { score: summary.score, at: now().toISOString(), mode: run.mode, week: run.week ?? isoWeekKey(now()) }]);
      const missions = dailyMissions(s.id, s.day.key).map((m) => {
        const prev = s.day.missions[m.key] ?? 0;
        const value = applyRunToMission(m, 0, summary);
        const progress = isCumulative(m.kind) ? prev + value : Math.max(prev, value);
        s.day.missions[m.key] = progress;
        return { key: m.key, label: m.label, progress: Math.min(progress, m.target), target: m.target, completed: progress >= m.target };
      });
      return { verified: true, mode: run.mode, event: null, summary, rewards: { fans: rewards.fans, credits: rewards.credits, gems: rewards.gems }, tiredAudience: rewards.tired, rewardsPaused: false, newBest, missions };
    }),
    leaderboard: (period) => read((s) => board(s, period)),
    daily: () => read((s) => {
      const check = checkDaily(s.daily, s.day.key);
      return { rewards: DAILY_REWARDS, streak: s.daily.streak, canClaim: check.canClaim, nextCycleDay: check.canClaim ? check.cycleDay : null, claimedToday: !check.canClaim };
    }),
    claimDaily: () => tx((s) => {
      const check = checkDaily(s.daily, s.day.key);
      if (!check.canClaim) throw err(409, 'already_claimed');
      s.daily = check.next;
      s.credits += check.reward.credits;
      s.gems += check.reward.gems;
      return { streak: check.next.streak, cycleDay: check.cycleDay, reward: check.reward };
    }),
    missions: () => read((s) => ({ day: s.day.key, missions: missionsView(s) })),
    claimMission: (key) => tx((s) => {
      const def = findMission(key);
      if (!def || !dailyMissions(s.id, s.day.key).some((m) => m.key === def.key)) throw err(404, 'mission_not_found');
      if (s.day.claimed.includes(def.key) || (s.day.missions[def.key] ?? 0) < def.target) throw err(409, 'mission_not_claimable');
      s.day.claimed.push(def.key);
      s.credits += def.reward;
      return { reward: def.reward };
    }),
    shop: () => read((s) => ({
      items: CATALOG.map((i) => ({ ...i, owned: owns(s, i.id) })),
      upgrades: UPGRADES.map((u) => {
        const level = s.upgrades[u.id] ?? 0;
        return { id: u.id, name: u.name, description: u.description, level, maxLevel: u.costs.length, nextCost: upgradeCost(u.id, level) };
      }),
    })),
    buy: (itemId) => tx((s) => {
      const item = findItem(itemId);
      if (!item) throw err(404, 'item_not_found');
      if (owns(s, itemId)) throw err(409, 'already_owned');
      spend(s, item.currency, item.price);
      s.owned.push(itemId);
      return { itemId };
    }),
    upgrade: (upgradeId) => tx((s) => {
      const def = UPGRADES.find((u) => u.id === upgradeId);
      if (!def) throw err(400, 'validation_error');
      const level = s.upgrades[def.id] ?? 0;
      const cost = upgradeCost(def.id, level);
      if (cost === null) throw err(409, 'max_level');
      spend(s, 'credits', cost);
      s.upgrades[def.id] = level + 1;
      return { level: level + 1 };
    }),
    inventory: () => read((s) => ({
      items: CATALOG.filter((c) => owns(s, c.id)).map((c) => ({ id: c.id, kind: c.kind, name: c.name })),
      upgrades: Object.entries(s.upgrades).map(([id, level]) => ({ id, level: level ?? 0 })),
      loadout: { skin: s.skin, character: s.character },
    })),
    loadout: (l) => tx((s) => {
      for (const [kind, id] of [['skin', l.skin], ['character', l.character]] as const) {
        if (!id) continue;
        const item = findItem(id);
        if (!item || item.kind !== kind) throw err(400, 'invalid_item');
        if (!owns(s, id)) throw err(403, 'not_owned');
        s[kind] = id;
      }
      return { skin: s.skin, character: s.character };
    }),
  };
}

export const localApi: Api = createLocalApi();
