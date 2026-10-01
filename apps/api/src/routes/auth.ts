import type { FastifyInstance } from 'fastify';
import { GuestAuthBody, LoginBody, RegisterBody } from '@stage/shared';
import { HttpError, parse } from '../errors';
import { hashDeviceId, hashPassword, verifyPassword } from '../password';
import { track } from '../app';

export async function authRoutes(app: FastifyInstance): Promise<void> {
  const { prisma, config } = app.deps;
  const strict = { config: { rateLimit: { max: config.AUTH_RATE_LIMIT_PER_MIN, timeWindow: '1 minute' } } };

  app.post('/auth/guest', strict, async (req) => {
    const { deviceId } = parse(GuestAuthBody, req.body);
    const deviceIdHash = hashDeviceId(deviceId, config.JWT_SECRET);
    let user = await prisma.user.findUnique({ where: { deviceIdHash } });
    if (!user) {
      // Multi-account farming guard: cap new guest accounts per IP per day.
      const recent = await prisma.user.count({ where: { createdIp: req.ip, createdAt: { gte: new Date(Date.now() - 86_400_000) } } });
      if (recent >= config.GUEST_ACCOUNTS_PER_IP_PER_DAY) throw new HttpError(429, 'too_many_accounts');
      user = await prisma.user.create({ data: { deviceIdHash, displayName: `Kuklacı${Math.floor(1000 + Math.random() * 9000)}`, createdIp: req.ip } });
      await track(prisma, user.id, 'signup', { method: 'guest' });
    }
    return { token: app.jwt.sign({ sub: user.id }) };
  });

  app.post('/auth/register', strict, async (req) => {
    const body = parse(RegisterBody, req.body);
    const email = body.email.toLowerCase();
    if (await prisma.user.findUnique({ where: { email } })) throw new HttpError(409, 'email_taken');
    const passwordHash = await hashPassword(body.password);
    let guestId: string | null = null;
    if (req.headers.authorization) {
      await app.authenticate(req);
      guestId = req.user.sub;
    }
    const user = guestId
      ? await prisma.user.update({ where: { id: guestId }, data: { email, passwordHash, ...(body.displayName ? { displayName: body.displayName } : {}) } })
      : await prisma.user.create({ data: { email, passwordHash, displayName: body.displayName ?? email.split('@')[0]!.slice(0, 20), createdIp: req.ip } });
    await track(prisma, user.id, 'signup', { method: 'email', upgradedGuest: !!guestId });
    return { token: app.jwt.sign({ sub: user.id }) };
  });

  app.post('/auth/login', strict, async (req) => {
    const body = parse(LoginBody, req.body);
    const user = await prisma.user.findUnique({ where: { email: body.email.toLowerCase() } });
    const ok = user?.passwordHash ? await verifyPassword(body.password, user.passwordHash) : false;
    if (!user || !ok) throw new HttpError(401, 'invalid_credentials');
    return { token: app.jwt.sign({ sub: user.id }) };
  });
}
