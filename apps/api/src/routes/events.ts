import type { FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';

/** The special event running right now, if any (earliest-ending wins when several overlap). */
export async function activeEvent(prisma: PrismaClient, now = new Date()) {
  return prisma.gameEvent.findFirst({ where: { startsAt: { lte: now }, endsAt: { gt: now } }, orderBy: { endsAt: 'asc' } });
}

export async function eventRoutes(app: FastifyInstance): Promise<void> {
  const { prisma } = app.deps;
  app.get('/events/active', { onRequest: [app.authenticate] }, async () => {
    const e = await activeEvent(prisma);
    return { event: e ? { id: e.id, name: e.name, description: e.description, endsAt: e.endsAt, fansMult: e.fansMult, creditsMult: e.creditsMult } : null };
  });
}
