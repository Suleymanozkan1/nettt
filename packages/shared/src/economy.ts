import type { RunSummary } from './sim';

/**
 * Economy is soft-currency only. Credits and gems are earned through play; there are no
 * real-money purchases, no loot boxes and no randomised paid rewards.
 */
export type Currency = 'credits' | 'gems';

export const RUN_CREDIT_CAP = 500;

export interface RunRewards { fans: number; credits: number; gems: number }

export function computeRunRewards(s: RunSummary): RunRewards {
  return {
    fans: s.fits + 2 * s.perfects + 25 * s.bossCleared,
    credits: Math.min(RUN_CREDIT_CAP, Math.floor(s.score / 2) + 20 * s.bossCleared),
    gems: s.bossCleared,
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
  { id: 'lamp_gold', kind: 'skin', name: 'Altın Fener', price: 25, currency: 'gems', colors: [0xffd700, 0xfff1a8] },
  { id: 'char_fox', kind: 'character', name: 'Kâğıt Tilki', price: 0, currency: 'credits', colors: [0xff8c42] },
  { id: 'char_owl', kind: 'character', name: 'Baykuş', price: 800, currency: 'credits', colors: [0x9b5de5] },
  { id: 'char_dragon', kind: 'character', name: 'Ejderha', price: 15, currency: 'gems', colors: [0x2ec4b6] },
];

export const DEFAULT_SKIN = 'lamp_candle';
export const DEFAULT_CHARACTER = 'char_fox';

export type UpgradeId = 'tolerance' | 'encore';
export interface UpgradeDef { id: UpgradeId; name: string; description: string; costs: number[] }

/** costs[i] = credits to go from level i to i+1. Max level = costs.length. */
export const UPGRADES: UpgradeDef[] = [
  { id: 'tolerance', name: 'Sabit Eller', description: 'Mükemmel uyum aralığı genişler', costs: [400, 900, 1600] },
  { id: 'encore', name: 'Bis!', description: '+1 başlangıç spot ışığı (can)', costs: [800, 2000] },
];

export function findItem(id: string): CatalogItem | undefined {
  return CATALOG.find((i) => i.id === id);
}

export function upgradeCost(id: UpgradeId, currentLevel: number): number | null {
  const def = UPGRADES.find((u) => u.id === id);
  if (!def) return null;
  return def.costs[currentLevel] ?? null;
}
