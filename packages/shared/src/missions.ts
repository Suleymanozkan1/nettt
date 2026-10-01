import { hashString, createRng } from './rng';
import type { RunSummary } from './sim';

export type MissionKind = 'runs' | 'fits' | 'perfects' | 'score' | 'combo';
export interface MissionDef { key: string; kind: MissionKind; target: number; reward: number; label: string }

const POOL: MissionDef[] = [
  { key: 'runs_3', kind: 'runs', target: 3, reward: 60, label: '3 gösteri oyna' },
  { key: 'runs_5', kind: 'runs', target: 5, reward: 100, label: '5 gösteri oyna' },
  { key: 'fits_25', kind: 'fits', target: 25, reward: 80, label: '25 gölgeyi kalıba oturt' },
  { key: 'fits_60', kind: 'fits', target: 60, reward: 150, label: '60 gölgeyi kalıba oturt' },
  { key: 'perfects_8', kind: 'perfects', target: 8, reward: 90, label: '8 mükemmel uyum yakala' },
  { key: 'perfects_20', kind: 'perfects', target: 20, reward: 160, label: '20 mükemmel uyum yakala' },
  { key: 'score_60', kind: 'score', target: 60, reward: 100, label: 'Tek gösteride 60 puan' },
  { key: 'score_120', kind: 'score', target: 120, reward: 180, label: 'Tek gösteride 120 puan' },
  { key: 'combo_4', kind: 'combo', target: 4, reward: 120, label: 'x4 combo yap' },
];

export const MISSIONS_PER_DAY = 3;

/** Three distinct missions per user per UTC day, derived deterministically (no stored randomness). */
export function dailyMissions(userId: string, day: string): MissionDef[] {
  const rng = createRng(hashString(`${userId}:${day}`));
  const pool = [...POOL];
  const out: MissionDef[] = [];
  while (out.length < MISSIONS_PER_DAY && pool.length) {
    const idx = Math.floor(rng() * pool.length);
    const [m] = pool.splice(idx, 1);
    if (m && !out.some((o) => o.kind === m.kind)) out.push(m);
  }
  return out;
}

export function findMission(key: string): MissionDef | undefined {
  return POOL.find((m) => m.key === key);
}

/** New progress value after a run. Cumulative kinds add up; best-of kinds keep the max. */
export function applyRunToMission(m: MissionDef, current: number, s: RunSummary): number {
  switch (m.kind) {
    case 'runs': return current + 1;
    case 'fits': return current + s.fits;
    case 'perfects': return current + s.perfects;
    case 'score': return Math.max(current, s.score);
    case 'combo': return Math.max(current, s.maxCombo);
  }
}

export function utcDay(d: Date = new Date()): string {
  return d.toISOString().slice(0, 10);
}

export function dayDiff(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);
}
