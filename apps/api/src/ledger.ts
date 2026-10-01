import type { Prisma, PrismaClient, Currency } from '@prisma/client';
import { HttpError } from './errors';

type Tx = Prisma.TransactionClient;

/**
 * All balance changes go through here. Balances are only ever changed with atomic increments in the
 * same DB transaction that writes the ledger row; the (userId, reason, refId, currency) unique key
 * makes every grant idempotent.
 */
export async function grant(tx: Tx, userId: string, currency: Currency, amount: number, reason: string, refId: string): Promise<number> {
  if (amount <= 0) return 0;
  const user = await tx.user.update({ where: { id: userId }, data: { [currency]: { increment: amount } }, select: { credits: true, gems: true } });
  await tx.transaction.create({ data: { userId, currency, amount, balanceAfter: user[currency], reason, refId } });
  return amount;
}

export async function spend(tx: Tx, userId: string, currency: Currency, amount: number, reason: string, refId: string): Promise<void> {
  if (amount <= 0) return;
  const res = await tx.user.updateMany({ where: { id: userId, [currency]: { gte: amount } }, data: { [currency]: { decrement: amount } } });
  if (res.count !== 1) throw new HttpError(402, 'insufficient_funds');
  const user = await tx.user.findUniqueOrThrow({ where: { id: userId }, select: { credits: true, gems: true } });
  await tx.transaction.create({ data: { userId, currency, amount: -amount, balanceAfter: user[currency], reason, refId } });
}

const BREAKER_KEY = 'rewards_paused';
const REWARD_REASONS = ['run', 'daily', 'mission'];
let cache: { at: number; paused: boolean } | null = null;

export function clearBreakerCache(): void { cache = null; }

/**
 * Economy circuit breaker. Rewards stop when an admin pauses them or when credits granted in the
 * last 24h exceed the configured liability limit (e.g. an exploit is minting currency).
 */
export async function rewardsPaused(prisma: PrismaClient, limit: number): Promise<boolean> {
  if (cache && Date.now() - cache.at < 10_000) return cache.paused;
  const row = await prisma.economyConfig.findUnique({ where: { key: BREAKER_KEY } });
  const value = row?.value as { paused?: boolean; by?: string } | null;
  let paused = value?.paused === true;
  // An admin decision made within the last 24h wins over the automatic check, so an admin can
  // resume rewards after investigating an auto-trip (the 24h sum would otherwise re-trip it).
  const adminOverride = !!row && value?.by?.startsWith('admin:') === true && Date.now() - row.updatedAt.getTime() < 86_400_000;
  if (!paused && !adminOverride) {
    const since = new Date(Date.now() - 86_400_000);
    const agg = await prisma.transaction.aggregate({ _sum: { amount: true }, where: { currency: 'credits', amount: { gt: 0 }, createdAt: { gte: since }, reason: { in: REWARD_REASONS } } });
    if ((agg._sum.amount ?? 0) > limit) {
      paused = true;
      await setRewardsPaused(prisma, true, 'auto_liability_limit');
    }
  }
  cache = { at: Date.now(), paused };
  return paused;
}

export async function setRewardsPaused(prisma: PrismaClient, paused: boolean, by: string): Promise<void> {
  await prisma.economyConfig.upsert({ where: { key: BREAKER_KEY }, create: { key: BREAKER_KEY, value: { paused, by } }, update: { value: { paused, by } } });
  await prisma.event.create({ data: { type: 'circuit_breaker', data: { paused, by } } });
  clearBreakerCache();
}
