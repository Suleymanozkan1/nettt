import { z } from 'zod';
import { MAX_INPUTS } from './rules';

export const GuestAuthBody = z.object({
  deviceId: z.string().min(16).max(128).regex(/^[A-Za-z0-9_-]+$/),
  powId: z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/),
  powNonce: z.string().min(1).max(12),
});
export const RegisterBody = z.object({ email: z.email().max(254), password: z.string().min(8).max(128), displayName: z.string().min(2).max(20).regex(/^[\p{L}\p{N} _-]+$/u).optional() });
export const LoginBody = z.object({ email: z.email().max(254), password: z.string().min(1).max(128) });
export const SettingsBody = z.object({
  sound: z.boolean().optional(),
  haptics: z.boolean().optional(),
  notifications: z.boolean().optional(),
  tutorialDone: z.boolean().optional(),
  displayName: z.string().min(2).max(20).regex(/^[\p{L}\p{N} _-]+$/u).optional(),
}).strict();
export const RunInputSchema = z.object({ t: z.number().int().min(0), k: z.enum(['tap', 'revive', 'quit']) });
export const StartRunBody = z.object({
  mode: z.enum(['normal', 'challenge']).default('normal'),
  startAct: z.number().int().min(1).max(50).default(1),
}).strict();
export const OnboardingBody = z.object({ displayName: z.string().min(2).max(20).regex(/^[\p{L}\p{N} _-]+$/u) }).strict();
export const RefundBody = z.object({ itemId: z.string().max(64) }).strict();
export const GameEventBody = z.object({
  name: z.string().min(2).max(60),
  description: z.string().max(200).default(''),
  startsAt: z.iso.datetime(),
  endsAt: z.iso.datetime(),
  fansMult: z.number().min(1).max(3).default(1),
  creditsMult: z.number().min(1).max(3).default(1),
}).strict();
export const FinishRunBody = z.object({ inputs: z.array(RunInputSchema).max(MAX_INPUTS) });
export const BuyBody = z.object({ itemId: z.string().max(64) });
export const UpgradeBody = z.object({ upgradeId: z.enum(['tolerance', 'encore']) });
export const LoadoutBody = z.object({ skin: z.string().max(64).optional(), character: z.string().max(64).optional() }).strict();
export const LeaderboardQuery = z.object({ period: z.enum(['all', 'weekly', 'challenge']).default('all') });
export const AdminEconomyBody = z.object({ paused: z.boolean() });

export interface Settings { sound: boolean; haptics: boolean; notifications: boolean }
export const DEFAULT_SETTINGS: Settings = { sound: true, haptics: true, notifications: false };
