import type { FastifyInstance, FastifyRequest } from 'fastify';
import { AdminEconomyBody, GameEventBody, LoginBody, RefundBody, findItem } from '@stage/shared';
import { verifyPassword } from '../password';

const ADMIN_COOKIE = 'golge_admin';
const ADMIN_SESSION_S = 8 * 60 * 60;
import { track } from '../app';
import { HttpError, parse } from '../errors';
import { rewardsPaused, setRewardsPaused } from '../ledger';
import { userId } from '../app';

export async function adminRoutes(app: FastifyInstance): Promise<void> {
  const { prisma, config } = app.deps;

  const origins = new Set(config.CORS_ORIGINS.split(',').map((s) => s.trim()).filter(Boolean));
  const cookieOpts = { httpOnly: true, sameSite: 'strict' as const, secure: config.NODE_ENV === 'production', path: '/', maxAge: ADMIN_SESSION_S };

  /**
   * Admin auth: either a Bearer token or the httpOnly `golge_admin` session cookie. Cookie-authenticated
   * state-changing requests must come from an allowed Origin (CSRF guard on top of SameSite=Strict).
   * The role is read from the database on every request, never trusted from the token.
   */
  async function requireAdmin(req: FastifyRequest): Promise<void> {
    const sessionToken = req.cookies[ADMIN_COOKIE];
    if (!req.headers.authorization && sessionToken) {
      if (req.method !== 'GET' && !origins.has(String(req.headers.origin ?? ''))) throw new HttpError(403, 'csrf_origin');
      let payload: { sub: string; tv: number; scope?: string };
      try { payload = app.jwt.verify(sessionToken); } catch { throw new HttpError(401, 'unauthorized'); }
      if (payload.scope !== 'admin') throw new HttpError(401, 'unauthorized');
      const u = await prisma.user.findUnique({ where: { id: payload.sub }, select: { tokenVersion: true } });
      if (!u || u.tokenVersion !== payload.tv) throw new HttpError(401, 'unauthorized');
      req.user = { sub: payload.sub, tv: payload.tv, scope: 'admin' };
    } else {
      await app.authenticate(req);
    }
    const user = await prisma.user.findUnique({ where: { id: userId(req) }, select: { role: true } });
    if (user?.role !== 'ADMIN') throw new HttpError(403, 'forbidden');
  }

  app.post('/auth/admin-session', { config: { rateLimit: { max: config.AUTH_RATE_LIMIT_PER_MIN, timeWindow: '1 minute' } } }, async (req, reply) => {
    if (!origins.has(String(req.headers.origin ?? ''))) throw new HttpError(403, 'csrf_origin');
    const body = parse(LoginBody, req.body);
    const user = await prisma.user.findUnique({ where: { email: body.email.toLowerCase() } });
    const ok = user?.passwordHash ? await verifyPassword(body.password, user.passwordHash) : false;
    if (!user || !ok || user.role !== 'ADMIN') throw new HttpError(401, 'invalid_credentials');
    const token = app.jwt.sign({ sub: user.id, tv: user.tokenVersion, scope: 'admin' }, { expiresIn: `${ADMIN_SESSION_S}s` });
    reply.setCookie(ADMIN_COOKIE, token, cookieOpts);
    await track(prisma, user.id, 'admin_login', {});
    return { ok: true };
  });

  app.post('/auth/admin-session/logout', async (req, reply) => {
    if (!origins.has(String(req.headers.origin ?? ''))) throw new HttpError(403, 'csrf_origin');
    reply.clearCookie(ADMIN_COOKIE, { path: '/' });
    return { ok: true };
  });
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

  app.get<{ Querystring: { q?: string } }>('/admin/users', admin, async (req) => {
    const q = String(req.query.q ?? '').slice(0, 64);
    const users = await prisma.user.findMany({
      where: q ? { OR: [{ id: q }, { displayName: { contains: q, mode: 'insensitive' } }, { email: { contains: q.toLowerCase() } }] } : { flagged: true },
      orderBy: { createdAt: 'desc' },
      take: 25,
      select: { id: true, displayName: true, email: true, credits: true, gems: true, bestScore: true, flagged: true, role: true, createdAt: true },
    });
    return { users };
  });

  /** Refund a purchased cosmetic: the item is removed and its price returned through the ledger (idempotent). */
  app.post<{ Params: { id: string } }>('/admin/users/:id/refund', admin, async (req) => {
    const { itemId } = parse(RefundBody, req.body);
    const item = findItem(itemId);
    if (!item || item.price === 0) throw new HttpError(404, 'item_not_found');
    const uid = req.params.id;
    return prisma.$transaction(async (tx) => {
      const removed = await tx.inventoryItem.deleteMany({ where: { userId: uid, itemId } });
      if (removed.count !== 1) throw new HttpError(409, 'not_owned');
      await tx.user.updateMany({ where: { id: uid, skin: itemId }, data: { skin: 'lamp_candle' } });
      await tx.user.updateMany({ where: { id: uid, character: itemId }, data: { character: 'char_fox' } });
      const refunds = await tx.transaction.count({ where: { userId: uid, reason: 'refund', refId: { startsWith: `${itemId}:` } } });
      const user = await tx.user.update({ where: { id: uid }, data: { [item.currency]: { increment: item.price } }, select: { credits: true, gems: true } });
      await tx.transaction.create({ data: { userId: uid, currency: item.currency, amount: item.price, balanceAfter: user[item.currency], reason: 'refund', refId: `${itemId}:${refunds + 1}` } });
      await track(tx, uid, 'refund', { itemId, by: userId(req) });
      return { itemId, refunded: item.price, currency: item.currency };
    });
  });

  app.get('/admin/events', admin, async () => ({ events: await prisma.gameEvent.findMany({ orderBy: { startsAt: 'desc' }, take: 50 }) }));

  app.post('/admin/events', admin, async (req) => {
    const body = parse(GameEventBody, req.body);
    if (Date.parse(body.endsAt) <= Date.parse(body.startsAt)) throw new HttpError(400, 'validation_error', 'endsAt must be after startsAt');
    return prisma.gameEvent.create({ data: { ...body, startsAt: new Date(body.startsAt), endsAt: new Date(body.endsAt) } });
  });

  app.delete<{ Params: { id: string } }>('/admin/events/:id', admin, async (req) => {
    await prisma.gameEvent.deleteMany({ where: { id: req.params.id } });
    return { ok: true };
  });

  app.post<{ Params: { id: string } }>('/admin/users/:id/unflag', admin, async (req) => {
    await prisma.user.update({ where: { id: req.params.id }, data: { flagged: false } });
    return { ok: true };
  });

  app.get('/admin/economy', admin, async () => {
    const since = new Date(Date.now() - 86_400_000);
    const [paused, granted24h, liability, runs24h, rejected24h, dau, duels24h, events24h] = await Promise.all([
      rewardsPaused(prisma, config.DAILY_CREDIT_LIABILITY_LIMIT),
      prisma.transaction.groupBy({ by: ['currency'], where: { amount: { gt: 0 }, createdAt: { gte: since } }, _sum: { amount: true } }),
      prisma.user.aggregate({ _sum: { credits: true, gems: true } }),
      prisma.run.count({ where: { status: 'FINISHED', finishedAt: { gte: since } } }),
      prisma.run.count({ where: { status: 'REJECTED', finishedAt: { gte: since } } }),
      prisma.event.groupBy({ by: ['userId'], where: { createdAt: { gte: since }, userId: { not: null } } }),
      prisma.duelMatch.count({ where: { createdAt: { gte: since } } }),
      prisma.event.groupBy({ by: ['type'], where: { createdAt: { gte: since } }, _count: { _all: true } }),
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
      duels24h,
      events24h: Object.fromEntries(events24h.map((e) => [e.type, e._count._all])),
    };
  });

  app.post('/admin/economy', admin, async (req) => {
    const { paused } = parse(AdminEconomyBody, req.body);
    await setRewardsPaused(prisma, paused, `admin:${userId(req)}`);
    return { rewardsPaused: paused };
  });
}
