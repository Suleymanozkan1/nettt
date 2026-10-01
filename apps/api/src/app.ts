import Fastify, { type FastifyInstance, type FastifyRequest } from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import jwt from '@fastify/jwt';
import rateLimit from '@fastify/rate-limit';
import { Prisma, type PrismaClient } from '@prisma/client';
import type { Redis } from 'ioredis';
import type { Config } from './config';
import { HttpError } from './errors';
import { authRoutes } from './routes/auth';
import { profileRoutes } from './routes/profile';
import { runRoutes } from './routes/runs';
import { economyRoutes } from './routes/economy';
import { leaderboardRoutes } from './routes/leaderboard';
import { adminRoutes } from './routes/admin';

export interface AppDeps { prisma: PrismaClient; config: Config; redis?: Redis; logger?: boolean }

declare module 'fastify' {
  interface FastifyInstance {
    deps: AppDeps;
    authenticate: (req: FastifyRequest) => Promise<void>;
  }
}
declare module '@fastify/jwt' {
  interface FastifyJWT { payload: { sub: string }; user: { sub: string } }
}

export async function buildApp(deps: AppDeps): Promise<FastifyInstance> {
  const app = Fastify({
    logger: deps.logger === false ? false : { level: 'info', redact: ['req.headers.authorization', 'req.body.password'] },
    bodyLimit: 256 * 1024,
    trustProxy: deps.config.TRUST_PROXY,
  });
  app.decorate('deps', deps);

  await app.register(helmet);
  const origins = deps.config.CORS_ORIGINS.split(',').map((s) => s.trim()).filter(Boolean);
  await app.register(cors, { origin: origins, methods: ['GET', 'POST', 'PATCH'] });
  await app.register(jwt, { secret: deps.config.JWT_SECRET, sign: { expiresIn: '30d' } });
  await app.register(rateLimit, {
    max: deps.config.RATE_LIMIT_PER_MIN,
    timeWindow: '1 minute',
    redis: deps.redis,
    nameSpace: 'stage-rl:',
    keyGenerator: (req) => req.ip,
  });

  app.decorate('authenticate', async (req: FastifyRequest) => {
    try {
      await req.jwtVerify();
    } catch {
      throw new HttpError(401, 'unauthorized');
    }
  });

  app.setErrorHandler((err: unknown, req, reply) => {
    if (err instanceof HttpError) return reply.status(err.statusCode).send({ error: err.code, message: err.message });
    if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === 'P2002') return reply.status(409).send({ error: 'conflict' });
    const e = err as { statusCode?: number; message?: string };
    if (e.statusCode && e.statusCode < 500) return reply.status(e.statusCode).send({ error: 'request_error', message: e.message });
    req.log.error(err);
    return reply.status(500).send({ error: 'internal_error' });
  });

  app.get('/health', async () => {
    await deps.prisma.$queryRaw`SELECT 1`;
    return { ok: true };
  });

  await app.register(authRoutes);
  await app.register(profileRoutes);
  await app.register(runRoutes);
  await app.register(economyRoutes);
  await app.register(leaderboardRoutes);
  await app.register(adminRoutes);
  return app;
}

export function userId(req: FastifyRequest): string {
  return req.user.sub;
}

export async function track(prisma: PrismaClient | Prisma.TransactionClient, userId: string | null, type: string, data: Prisma.InputJsonValue = {}): Promise<void> {
  await prisma.event.create({ data: { userId, type, data } });
}
