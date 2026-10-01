/**
 * Gölge Kuklacı (Shadow Puppeteer) — tunable gameplay constants, shared by the Phaser client and the
 * server-side replay validator.
 *
 * A lamp swings like a pendulum behind a puppet. The puppet's shadow slides across the wall and grows as
 * the lamp swings out. A silhouette hole appears on the wall; tap to freeze the lamp when the shadow fits
 * the hole. Fit quality → perfect / good / miss. Misses and timeouts cost a spotlight (life).
 */
export const WORLD_WIDTH = 400;
export const WALL_CENTER_X = 200;
export const WALL_Y = 250;
export const SHADOW_SWING_X = 150; // horizontal shadow travel at max swing
export const SHADOW_GROWTH = 0.9; // shadow scale goes 1 → 1 + growth at max swing
export const MAX_SWING = 0.9; // radians
export const HOLE_SWING_LIMIT = 0.85; // holes sit within 85% of the swing so they are always reachable
export const X_TOLERANCE = 60; // world units that count as a full "1.0" fit error horizontally
export const SCALE_TOLERANCE = 0.35; // scale difference that counts as a full "1.0" fit error
export const BASE_PERFECT_ERROR = 0.15;
export const GOOD_ERROR = 1;
export const BOSS_PERFECT_MULT = 0.7;
export const BOSS_DRIFT_X = 30;
export const BOSS_DRIFT_PERIOD = 350;
export const BASE_PERIOD_MS = 2200;
export const SPEED_STEP = 0.08;
export const MAX_SPEED_MULT = 2.0;
export const ROUND_DELAY_MS = 600;
export const REVIVE_DELAY_MS = 800;
export const ROUNDS_PER_LEVEL = 10;
export const BOSS_EVERY = 5;
export const START_LIVES = 3;
export const COMBO_SCORE_CAP = 10;
export const MAX_REVIVES = 1;
export const REVIVE_GEM_COST = 5;
export const MAX_INPUTS = 3000;
export const MAX_RUN_MS = 30 * 60 * 1000;

export const SHAPES = ['cat', 'bird', 'rabbit', 'fish', 'star', 'moon', 'tree', 'butterfly'] as const;
export type ShapeId = (typeof SHAPES)[number];

export interface RunParams {
  /** Upgrade level 0-3: wider "perfect" window. */
  toleranceLevel: number;
  /** Upgrade level 0-2: extra starting spotlights (lives). */
  encoreLevel: number;
}

export const DEFAULT_PARAMS: RunParams = { toleranceLevel: 0, encoreLevel: 0 };

export function levelForRounds(rounds: number): number {
  return Math.floor(rounds / ROUNDS_PER_LEVEL) + 1;
}

export function isBossLevel(level: number): boolean {
  return level % BOSS_EVERY === 0;
}

/** Angular frequency (rad/ms) of the lamp; faster each level, capped. */
export function omegaForLevel(level: number): number {
  return ((2 * Math.PI) / BASE_PERIOD_MS) * Math.min(1 + SPEED_STEP * (level - 1), MAX_SPEED_MULT);
}

/** Share of a second, faster harmonic in the swing — later acts feel less predictable. */
export function wobbleForLevel(level: number): number {
  return Math.min(0.35, 0.05 * (level - 1));
}

export function roundTimeMs(level: number): number {
  return Math.max(2600, 5200 - 250 * (level - 1));
}

export function perfectError(level: number, params: RunParams): number {
  const base = BASE_PERFECT_ERROR + 0.03 * params.toleranceLevel;
  return isBossLevel(level) ? base * BOSS_PERFECT_MULT : base;
}

export function startLives(params: RunParams): number {
  return START_LIVES + params.encoreLevel;
}

export function shadowX(theta: number): number {
  return WALL_CENTER_X - SHADOW_SWING_X * Math.sin(theta) / Math.sin(MAX_SWING);
}

export function shadowScale(theta: number): number {
  return 1 + SHADOW_GROWTH * (1 - Math.cos(theta)) / (1 - Math.cos(MAX_SWING));
}
