import type { FastifyInstance, FastifyRequest } from 'fastify';
import { AdminEconomyBody } from '@stage/shared';
import { HttpError, parse } from '../errors';
import { rewardsPaused, setRewardsPaused } from '../ledger';
import { userId } from '../app';

export async function adminRoutes(app: FastifyInstance): Promise<void> {
  const { prisma, config } = app.deps;

  // Role is read from the database on every request, never trusted from the token.
  async function requireAdmin(req: FastifyRequest): Promise<void> {
    await app.authenticate(req);
    const user = await prisma.user.findUnique({ where: { id: userId(req) }, select: { role: true } });
    if (user?.role !== 'ADMIN') throw new HttpError(403, 'forbidden');
  }
  const admin = { onRequest: [requireAdmin] };

  app.get<{ Params: { id: string } }>('/admin/users/:id', admin, async (req) => {
    const user = await prisma.user.findUnique({
      where: { id: req.params.id },
      select: { id: true, displayName: true, email: true, role: true, credits: true, gems: true, fans: true, bestScore: true, totalRuns: true, flagged: true, createdAt: true },
    });
    if (!user) throw new HttpError(404, 'user_not_found');
    const runs = await prisma.run.findMany({ where: { userId: user.id }, orderBy: { startedAt: 'desc' }, take: 20 });
    return { user, runs };
  });

  app.get<{ Querystring: { userId?: string } }>('/admin/transactions', admin, async (req) => {
    const where = req.query.userId ? { userId: String(req.query.userId) } : {};
    return { transactions: await prisma.transaction.findMany({ where, orderBy: { createdAt: 'desc' }, take: 100 }) };
  });

  app.post<{ Params: { id: string } }>('/admin/users/:id/unflag', admin, async (req) => {
    await prisma.user.update({ where: { id: req.params.id }, data: { flagged: false } });
    return { ok: true };
  });

  app.get('/admin/economy', admin, async () => {
    const since = new Date(Date.now() - 86_400_000);
    const [paused, granted24h, liability, runs24h, rejected24h, dau] = await Promise.all([
      rewardsPaused(prisma, config.DAILY_CREDIT_LIABILITY_LIMIT),
      prisma.transaction.groupBy({ by: ['currency'], where: { amount: { gt: 0 }, createdAt: { gte: since } }, _sum: { amount: true } }),
      prisma.user.aggregate({ _sum: { credits: true, gems: true } }),
      prisma.run.count({ where: { status: 'FINISHED', finishedAt: { gte: since } } }),
      prisma.run.count({ where: { status: 'REJECTED', finishedAt: { gte: since } } }),
      prisma.event.groupBy({ by: ['userId'], where: { createdAt: { gte: since }, userId: { not: null } } }),
    ]);
    return {
      rewardsPaused: paused,
      liabilityLimit24h: config.DAILY_CREDIT_LIABILITY_LIMIT,
      granted24h: Object.fromEntries(granted24h.map((g) => [g.currency, g._sum.amount ?? 0])),
      // Reward liability = soft currency held by players that can still be spent.
      outstanding: { credits: liability._sum.credits ?? 0, gems: liability._sum.gems ?? 0 },
      runs24h,
      rejected24h,
      dau: dau.length,
    };
  });

  app.post('/admin/economy', admin, async (req) => {
    const { paused } = parse(AdminEconomyBody, req.body);
    await setRewardsPaused(prisma, paused, `admin:${userId(req)}`);
    return { rewardsPaused: paused };
  });
}
