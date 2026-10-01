import type { FastifyInstance } from 'fastify';
import {
  BuyBody, CATALOG, DAILY_REWARDS, LoadoutBody, UPGRADES, UpgradeBody, checkDaily, dailyMissions, findItem,
  findMission, upgradeCost, utcDay,
} from '@stage/shared';
import { HttpError, parse } from '../errors';
import { grant, rewardsPaused, spend } from '../ledger';
import { track, userId } from '../app';

export async function economyRoutes(app: FastifyInstance): Promise<void> {
  const { prisma, config } = app.deps;
  const auth = { onRequest: [app.authenticate] };

  async function ensureRewardsOpen(): Promise<void> {
    if (await rewardsPaused(prisma, config.DAILY_CREDIT_LIABILITY_LIMIT)) throw new HttpError(503, 'rewards_paused');
  }

  // ---------- Daily reward ----------
  app.get('/daily', auth, async (req) => {
    const uid = userId(req);
    const state = await prisma.dailyState.findUnique({ where: { userId: uid } });
    const check = checkDaily({ streak: state?.streak ?? 0, lastClaimDay: state?.lastClaimDay ?? null, graceUsed: state?.graceUsed ?? false }, utcDay());
    return {
      rewards: DAILY_REWARDS,
      streak: state?.streak ?? 0,
      canClaim: check.canClaim,
      nextCycleDay: check.canClaim ? check.cycleDay : null,
      claimedToday: !check.canClaim,
    };
  });

  app.post('/daily/claim', auth, async (req) => {
    const uid = userId(req);
    await ensureRewardsOpen();
    const today = utcDay();
    return prisma.$transaction(async (tx) => {
      const state = await tx.dailyState.findUnique({ where: { userId: uid } });
      const check = checkDaily({ streak: state?.streak ?? 0, lastClaimDay: state?.lastClaimDay ?? null, graceUsed: state?.graceUsed ?? false }, today);
      if (!check.canClaim) throw new HttpError(409, 'already_claimed');
      await tx.dailyState.upsert({ where: { userId: uid }, create: { userId: uid, ...check.next }, update: check.next });
      // refId = day: the ledger unique key makes concurrent double-claims fail.
      await grant(tx, uid, 'credits', check.reward.credits, 'daily', today);
      await grant(tx, uid, 'gems', check.reward.gems, 'daily', today);
      await track(tx, uid, 'daily_claim', { day: today, streak: check.next.streak });
      return { streak: check.next.streak, cycleDay: check.cycleDay, reward: check.reward };
    });
  });

  // ---------- Missions ----------
  app.get('/missions', auth, async (req) => {
    const uid = userId(req);
    const day = utcDay();
    const rows = await prisma.missionProgress.findMany({ where: { userId: uid, day } });
    return {
      day,
      missions: dailyMissions(uid, day).map((m) => {
        const row = rows.find((r) => r.missionKey === m.key);
        const progress = row?.progress ?? 0;
        return { key: m.key, label: m.label, target: m.target, reward: m.reward, progress: Math.min(progress, m.target), completed: progress >= m.target, claimed: !!row?.claimedAt };
      }),
    };
  });

  app.post<{ Params: { key: string } }>('/missions/:key/claim', auth, async (req) => {
    const uid = userId(req);
    await ensureRewardsOpen();
    const day = utcDay();
    const def = findMission(req.params.key);
    if (!def || !dailyMissions(uid, day).some((m) => m.key === def.key)) throw new HttpError(404, 'mission_not_found');
    return prisma.$transaction(async (tx) => {
      const claimed = await tx.missionProgress.updateMany({
        where: { userId: uid, day, missionKey: def.key, claimedAt: null, progress: { gte: def.target } },
        data: { claimedAt: new Date() },
      });
      if (claimed.count !== 1) throw new HttpError(409, 'mission_not_claimable');
      await grant(tx, uid, 'credits', def.reward, 'mission', `${day}:${def.key}`);
      await track(tx, uid, 'mission_claim', { key: def.key, day });
      return { key: def.key, reward: def.reward };
    });
  });

  // ---------- Shop / inventory / upgrades ----------
  app.get('/shop', auth, async (req) => {
    const uid = userId(req);
    const [owned, upgrades] = await Promise.all([
      prisma.inventoryItem.findMany({ where: { userId: uid } }),
      prisma.upgradeLevel.findMany({ where: { userId: uid } }),
    ]);
    return {
      items: CATALOG.map((i) => ({ ...i, owned: i.price === 0 || owned.some((o) => o.itemId === i.id) })),
      upgrades: UPGRADES.map((u) => {
        const level = upgrades.find((x) => x.upgradeId === u.id)?.level ?? 0;
        return { id: u.id, name: u.name, description: u.description, level, maxLevel: u.costs.length, nextCost: upgradeCost(u.id, level) };
      }),
    };
  });

  app.post('/shop/buy', auth, async (req) => {
    const uid = userId(req);
    const { itemId } = parse(BuyBody, req.body);
    const item = findItem(itemId);
    if (!item) throw new HttpError(404, 'item_not_found');
    if (item.price === 0) throw new HttpError(409, 'already_owned');
    return prisma.$transaction(async (tx) => {
      if (await tx.inventoryItem.findUnique({ where: { userId_itemId: { userId: uid, itemId } } })) throw new HttpError(409, 'already_owned');
      // Unique (userId, itemId) blocks duplicates even under concurrent requests.
      await tx.inventoryItem.create({ data: { userId: uid, itemId } });
      await spend(tx, uid, item.currency, item.price, 'purchase', itemId);
      await track(tx, uid, 'purchase', { itemId, price: item.price, currency: item.currency });
      return { itemId, owned: true };
    });
  });

  app.post('/shop/upgrade', auth, async (req) => {
    const uid = userId(req);
    const { upgradeId } = parse(UpgradeBody, req.body);
    return prisma.$transaction(async (tx) => {
      const row = await tx.upgradeLevel.upsert({
        where: { userId_upgradeId: { userId: uid, upgradeId } },
        create: { userId: uid, upgradeId, level: 0 },
        update: {},
      });
      const cost = upgradeCost(upgradeId, row.level);
      if (cost === null) throw new HttpError(409, 'max_level');
      const bumped = await tx.upgradeLevel.updateMany({ where: { id: row.id, level: row.level }, data: { level: row.level + 1 } });
      if (bumped.count !== 1) throw new HttpError(409, 'concurrent_upgrade');
      await spend(tx, uid, 'credits', cost, 'upgrade', `${upgradeId}:${row.level + 1}`);
      await track(tx, uid, 'upgrade', { upgradeId, level: row.level + 1, cost });
      return { upgradeId, level: row.level + 1 };
    });
  });

  app.get('/inventory', auth, async (req) => {
    const uid = userId(req);
    const [user, owned, upgrades] = await Promise.all([
      prisma.user.findUniqueOrThrow({ where: { id: uid }, select: { skin: true, character: true } }),
      prisma.inventoryItem.findMany({ where: { userId: uid }, orderBy: { acquiredAt: 'asc' } }),
      prisma.upgradeLevel.findMany({ where: { userId: uid } }),
    ]);
    const ownedIds = new Set([...CATALOG.filter((c) => c.price === 0).map((c) => c.id), ...owned.map((o) => o.itemId)]);
    return {
      items: CATALOG.filter((c) => ownedIds.has(c.id)),
      upgrades: upgrades.map((u) => ({ id: u.upgradeId, level: u.level })),
      loadout: user,
    };
  });

  app.post('/loadout', auth, async (req) => {
    const uid = userId(req);
    const body = parse(LoadoutBody, req.body);
    const check = async (id: string | undefined, kind: 'skin' | 'character') => {
      if (!id) return;
      const item = findItem(id);
      if (!item || item.kind !== kind) throw new HttpError(400, 'invalid_item');
      if (item.price > 0 && !(await prisma.inventoryItem.findUnique({ where: { userId_itemId: { userId: uid, itemId: id } } }))) throw new HttpError(403, 'not_owned');
    };
    await check(body.skin, 'skin');
    await check(body.character, 'character');
    const user = await prisma.user.update({ where: { id: uid }, data: body, select: { skin: true, character: true } });
    return user;
  });
}
