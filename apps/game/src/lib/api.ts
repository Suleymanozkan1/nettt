import { solvePow, type RunInput, type RunParams, type RunSummary, type Settings, type MissionKind } from '@stage/shared';
import { storage } from './storage';
import { ApiError } from './api-error';
import { localApi } from './local-backend';

const BASE = (import.meta.env.VITE_API_URL as string | undefined) ?? 'http://localhost:3000';
const TOKEN_KEY = 'stage.token';
const DEVICE_KEY = 'stage.device';

export { ApiError };
/** Offline build (standalone APK): progression lives on the device, no server needed. */
export const LOCAL_BACKEND = import.meta.env.VITE_BACKEND === 'local';

let token: string | null = null;

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (token) headers.authorization = `Bearer ${token}`;
  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, 'offline');
  }
  const parsed: unknown = await res.json().catch(() => ({}));
  if (!res.ok) {
    // Error bodies may be anything (even `null`); never let that turn into a TypeError instead of an ApiError.
    const data = (parsed && typeof parsed === 'object' ? parsed : {}) as { error?: string; reason?: string; message?: string };
    throw new ApiError(res.status, data.error ?? 'error', data.reason ?? data.message);
  }
  return parsed as T;
}

function randomDeviceId(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export interface Profile {
  id: string; displayName: string; registered: boolean; role: string; credits: number; gems: number; fans: number;
  level: number; levelFloor: number; nextLevelFans: number; bestScore: number; totalRuns: number; rank: number | null;
  skin: string; character: string; tutorialDone: boolean; settings: Settings; reviveCost: number;
  onboarded: boolean; maxAct: number; startActs: number[];
}
export interface ActiveEvent { id: string; name: string; description: string; endsAt: string; fansMult: number; creditsMult: number }
export type RunMode = 'normal' | 'challenge';
export interface Mission { key: string; label: string; target: number; reward: number; progress: number; completed: boolean; claimed: boolean; kind?: MissionKind }
export interface FinishResult {
  verified: boolean; summary: RunSummary; rewards: { fans: number; credits: number; gems: number };
  rewardsPaused: boolean; newBest: boolean; mode: RunMode; event: { name: string } | null; tiredAudience: boolean; missions: { key: string; label: string; progress: number; target: number; completed: boolean }[];
}
export interface ShopData {
  items: { id: string; kind: 'skin' | 'character'; name: string; price: number; currency: 'credits' | 'gems'; colors: number[]; owned: boolean }[];
  upgrades: { id: 'tolerance' | 'encore'; name: string; description: string; level: number; maxLevel: number; nextCost: number | null }[];
}
export interface LeaderboardData { period: string; entries: { rank: number; name: string; score: number; character: string; me: boolean }[]; myRank: number | null; myScore: number }
export interface DailyData { rewards: { credits: number; gems: number }[]; streak: number; canClaim: boolean; nextCycleDay: number | null; claimedToday: boolean }

const remoteApi = {
  async init(): Promise<void> {
    token = await storage.get(TOKEN_KEY);
    if (token) return;
    let device = await storage.get(DEVICE_KEY);
    if (!device) { device = randomDeviceId(); await storage.set(DEVICE_KEY, device); }
    // Proof-of-work (anti account-farming): solved once per sign-in, typically well under a second.
    const ch = await request<{ id: string; salt: string; bits: number }>('GET', '/auth/challenge');
    const r = await request<{ token: string }>('POST', '/auth/guest', { deviceId: device, powId: ch.id, powNonce: solvePow(ch.salt, ch.bits) });
    token = r.token;
    await storage.set(TOKEN_KEY, token);
  },
  async setToken(t: string): Promise<void> { token = t; await storage.set(TOKEN_KEY, t); },
  /** Current bearer token, used to authenticate the realtime (Colyseus) connection. */
  token: (): string | null => token,
  async logout(): Promise<void> { token = null; await storage.remove(TOKEN_KEY); },
  me: () => request<Profile>('GET', '/me'),
  updateSettings: (s: Partial<Settings> & { tutorialDone?: boolean; displayName?: string }) => request<{ settings: Settings }>('PATCH', '/me/settings', s),
  register: (email: string, password: string, displayName?: string) => request<{ token: string }>('POST', '/auth/register', { email, password, ...(displayName ? { displayName } : {}) }),
  login: (email: string, password: string) => request<{ token: string }>('POST', '/auth/login', { email, password }),
  startRun: (body: { mode?: RunMode; startAct?: number } = {}) => request<{ runId: string; seed: number; params: RunParams; mode: RunMode; week?: string; attemptsLeft?: number }>('POST', '/runs', body),
  onboarding: (displayName: string) => request<{ displayName: string }>('POST', '/me/onboarding', { displayName }),
  logoutAll: () => request<{ ok: boolean }>('POST', '/auth/logout-all'),
  registerPush: (pushToken: string, platform: 'android' | 'ios') => request<{ ok: boolean }>('POST', '/me/push-token', { token: pushToken, platform }),
  activeEvent: () => request<{ event: ActiveEvent | null }>('GET', '/events/active'),
  finishRun: (runId: string, inputs: RunInput[]) => request<FinishResult>('POST', `/runs/${runId}/finish`, { inputs }),
  leaderboard: (period: 'all' | 'weekly' | 'challenge') => request<LeaderboardData>('GET', `/leaderboard?period=${period}`),
  daily: () => request<DailyData>('GET', '/daily'),
  claimDaily: () => request<{ streak: number; cycleDay: number; reward: { credits: number; gems: number } }>('POST', '/daily/claim'),
  missions: () => request<{ day: string; missions: Mission[] }>('GET', '/missions'),
  claimMission: (key: string) => request<{ reward: number }>('POST', `/missions/${encodeURIComponent(key)}/claim`),
  shop: () => request<ShopData>('GET', '/shop'),
  buy: (itemId: string) => request<{ itemId: string }>('POST', '/shop/buy', { itemId }),
  upgrade: (upgradeId: string) => request<{ level: number }>('POST', '/shop/upgrade', { upgradeId }),
  inventory: () => request<{ items: { id: string; kind: 'skin' | 'character'; name: string }[]; upgrades: { id: string; level: number }[]; loadout: { skin: string; character: string } }>('GET', '/inventory'),
  loadout: (l: { skin?: string; character?: string }) => request<{ skin: string; character: string }>('POST', '/loadout', l),
};

export type Api = typeof remoteApi;
export const api: Api = LOCAL_BACKEND ? localApi : remoteApi;
