import type { FastifyInstance } from 'fastify';
import type { Prisma } from '@prisma/client';
import { DEFAULT_SETTINGS, SettingsBody, playerLevelForFans, fansForPlayerLevel, REVIVE_GEM_COST, type Settings } from '@stage/shared';
import { HttpError, parse } from '../errors';
import { userId } from '../app';

export async function profileRoutes(app: FastifyInstance): Promise<void> {
  const { prisma } = app.deps;

  app.get('/me', { onRequest: [app.authenticate] }, async (req) => {
    const user = await prisma.user.findUnique({ where: { id: userId(req) } });
    if (!user) throw new HttpError(401, 'unauthorized');
    const level = playerLevelForFans(user.fans);
    const rank = user.bestScore > 0 ? (await prisma.user.count({ where: { bestScore: { gt: user.bestScore }, flagged: false } })) + 1 : null;
    return {
      id: user.id,
      displayName: user.displayName,
      registered: !!user.email,
      role: user.role,
      credits: user.credits,
      gems: user.gems,
      fans: user.fans,
      level,
      levelFloor: fansForPlayerLevel(level),
      nextLevelFans: fansForPlayerLevel(level + 1),
      bestScore: user.bestScore,
      totalRuns: user.totalRuns,
      rank,
      skin: user.skin,
      character: user.character,
      tutorialDone: user.tutorialDone,
      settings: { ...DEFAULT_SETTINGS, ...(user.settings as Partial<Settings>) },
      reviveCost: REVIVE_GEM_COST,
    };
  });

  app.patch('/me/settings', { onRequest: [app.authenticate] }, async (req) => {
    const body = parse(SettingsBody, req.body);
    const user = await prisma.user.findUniqueOrThrow({ where: { id: userId(req) } });
    const { tutorialDone, displayName, ...rest } = body;
    const settings = { ...DEFAULT_SETTINGS, ...(user.settings as Partial<Settings>), ...rest };
    await prisma.user.update({
      where: { id: user.id },
      data: { settings: settings as Prisma.InputJsonValue, ...(tutorialDone !== undefined ? { tutorialDone } : {}), ...(displayName ? { displayName } : {}) },
    });
    return { settings, tutorialDone: tutorialDone ?? user.tutorialDone };
  });
}
