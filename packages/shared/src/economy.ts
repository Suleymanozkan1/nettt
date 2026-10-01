import type { RunSummary } from './sim';

/**
 * Economy is soft-currency only. Credits and gems are earned through play; there are no
 * real-money purchases, no loot boxes and no randomised paid rewards.
 */
export type Currency = 'credits' | 'gems';

export const RUN_CREDIT_CAP = 150;
/** Shows per UTC day that pay full credits; later shows pay TIRED_AUDIENCE_MULT (fans are never reduced). */
export const FULL_REWARD_SHOWS_PER_DAY = 10;
export const TIRED_AUDIENCE_MULT = 0.25;
/** Gems from shows (boss clears) per UTC day. */
export const RUN_GEMS_PER_DAY = 3;

export interface RunRewards { fans: number; credits: number; gems: number }

export interface RewardMultipliers { fans: number; credits: number }

/** Special events may boost fans/credits (bounded to ×1–×3). Gems are never multiplied. */
export function computeRunRewards(s: RunSummary, mult: RewardMultipliers = { fans: 1, credits: 1 }): RunRewards {
  const clamp = (m: number) => Math.min(3, Math.max(1, m));
  const credits = Math.floor((Math.floor(s.score / 5) + 10 * s.bossCleared) * clamp(mult.credits));
  return {
    fans: Math.floor((s.fits + 2 * s.perfects + 25 * s.bossCleared) * clamp(mult.fans)),
    credits: Math.min(RUN_CREDIT_CAP, credits),
    gems: s.bossCleared,
  };
}

/**
 * Daily pacing (transparent, shown in the results): after FULL_REWARD_SHOWS_PER_DAY shows the audience is
 * "tired" and credits drop to 25%; gems from shows are capped per day. Fans are never reduced.
 * `showsBefore` / `gemsBefore` count today's earlier verified shows and show-gems.
 */
export function applyDailyPacing(r: RunRewards, showsBefore: number, gemsBefore: number): RunRewards & { tired: boolean } {
  const tired = showsBefore >= FULL_REWARD_SHOWS_PER_DAY;
  return {
    fans: r.fans,
    credits: tired ? Math.floor(r.credits * TIRED_AUDIENCE_MULT) : r.credits,
    gems: Math.max(0, Math.min(r.gems, RUN_GEMS_PER_DAY - gemsBefore)),
    tired,
  };
}

/** Player level from lifetime fans: level n needs 100 * n * (n - 1) / 2 fans (100, 300, 600, ...). */
export function playerLevelForFans(fans: number): number {
  let level = 1;
  while (fans >= (100 * level * (level + 1)) / 2) level += 1;
  return level;
}

export function fansForPlayerLevel(level: number): number {
  return (100 * level * (level - 1)) / 2;
}

/** 7-day cycle. Missing a day uses one grace day; otherwise the cycle restarts at day 1. Nothing owned is ever lost. */
export const DAILY_REWARDS: { credits: number; gems: number }[] = [
  { credits: 50, gems: 0 },
  { credits: 75, gems: 0 },
  { credits: 100, gems: 0 },
  { credits: 125, gems: 0 },
  { credits: 150, gems: 1 },
  { credits: 200, gems: 0 },
  { credits: 300, gems: 3 },
];

export type ItemKind = 'skin' | 'character';
export interface CatalogItem { id: string; kind: ItemKind; name: string; price: number; currency: Currency; colors: number[] }

/** Lamps tint the light and the silhouette glow; characters are the puppet on the stick. */
export const CATALOG: CatalogItem[] = [
  { id: 'lamp_candle', kind: 'skin', name: 'Mum Işığı', price: 0, currency: 'credits', colors: [0xffc46b, 0xff9f43] },
  { id: 'lamp_neon', kind: 'skin', name: 'Neon Ampul', price: 300, currency: 'credits', colors: [0xff00e5, 0x00f0ff] },
  { id: 'lamp_moon', kind: 'skin', name: 'Ay Işığı', price: 600, currency: 'credits', colors: [0xbde0fe, 0x8ecae6] },
  { id: 'lamp_aurora', kind: 'skin', name: 'Kutup Işığı', price: 2000, currency: 'credits', colors: [0x7cffcb, 0x74b3ff] },
  { id: 'lamp_crystal', kind: 'skin', name: 'Kristal Avize', price: 4500, currency: 'credits', colors: [0xe0f7ff, 0xb388ff] },
  { id: 'lamp_gold', kind: 'skin', name: 'Altın Fener', price: 25, currency: 'gems', colors: [0xffd700, 0xfff1a8] },
  { id: 'lamp_ember', kind: 'skin', name: 'Kor Ateşi', price: 60, currency: 'gems', colors: [0xff5e3a, 0xffb199] },
  { id: 'char_fox', kind: 'character', name: 'Kâğıt Tilki', price: 0, currency: 'credits', colors: [0xff8c42] },
  { id: 'char_owl', kind: 'character', name: 'Baykuş', price: 800, currency: 'credits', colors: [0x9b5de5] },
  { id: 'char_whale', kind: 'character', name: 'Balina', price: 2500, currency: 'credits', colors: [0x3a86ff] },
  { id: 'char_phoenix', kind: 'character', name: 'Anka Kuşu', price: 6000, currency: 'credits', colors: [0xff006e] },
  { id: 'char_dragon', kind: 'character', name: 'Ejderha', price: 15, currency: 'gems', colors: [0x2ec4b6] },
  { id: 'char_unicorn', kind: 'character', name: 'Tek Boynuz', price: 45, currency: 'gems', colors: [0xf5d0fe] },
];

export const DEFAULT_SKIN = 'lamp_candle';
export const DEFAULT_CHARACTER = 'char_fox';

export type UpgradeId = 'tolerance' | 'encore';
export interface UpgradeDef { id: UpgradeId; name: string; description: string; costs: number[] }

/** costs[i] = credits to go from level i to i+1. Max level = costs.length. */
export const UPGRADES: UpgradeDef[] = [
  { id: 'tolerance', name: 'Sabit Eller', description: 'Mükemmel uyum aralığı genişler', costs: [600, 1500, 3000] },
  { id: 'encore', name: 'Bis!', description: '+1 başlangıç spot ışığı (can)', costs: [1500, 4000] },
];

export function findItem(id: string): CatalogItem | undefined {
  return CATALOG.find((i) => i.id === id);
}

export function upgradeCost(id: UpgradeId, currentLevel: number): number | null {
  const def = UPGRADES.find((u) => u.id === id);
  if (!def) return null;
  return def.costs[currentLevel] ?? null;
}
