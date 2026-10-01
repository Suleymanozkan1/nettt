import type { FastifyInstance } from 'fastify';
import { Prisma } from '@prisma/client';
import { LeaderboardQuery, isoWeekKey } from '@stage/shared';
import { parse } from '../errors';
import { userId } from '../app';

const TOP_N = 50;

/** Monday 00:00 UTC of the current week. */
export function weekStart(now = new Date()): Date {
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const dow = (d.getUTCDay() + 6) % 7;
  d.setUTCDate(d.getUTCDate() - dow);
  return d;
}

export async function leaderboardRoutes(app: FastifyInstance): Promise<void> {
  const { prisma } = app.deps;

  app.get('/leaderboard', { onRequest: [app.authenticate] }, async (req) => {
    const { period } = parse(LeaderboardQuery, req.query);
    const me = userId(req);
    if (period === 'all') {
      const users = await prisma.user.findMany({
        where: { flagged: false, bestScore: { gt: 0 } },
        orderBy: [{ bestScore: 'desc' }, { createdAt: 'asc' }],
        take: TOP_N,
        select: { id: true, displayName: true, bestScore: true, character: true },
      });
      const mine = await prisma.user.findUniqueOrThrow({ where: { id: me }, select: { bestScore: true } });
      const myRank = mine.bestScore > 0 ? (await prisma.user.count({ where: { flagged: false, bestScore: { gt: mine.bestScore } } })) + 1 : null;
      return { period, entries: users.map((u, i) => ({ rank: i + 1, name: u.displayName, score: u.bestScore, character: u.character, me: u.id === me })), myRank, myScore: mine.bestScore };
    }
    // Weekly (normal shows since Monday 00:00 UTC) or the weekly challenge (shared seed this week).
    const since = weekStart();
    const where = period === 'challenge'
      ? { status: 'FINISHED' as const, mode: 'challenge', challengeWeek: isoWeekKey(), user: { flagged: false } }
      : { status: 'FINISHED' as const, mode: 'normal', finishedAt: { gte: since }, user: { flagged: false } };
    // Top N only (bounded), then my rank from a separate aggregate instead of loading every player.
    const top = await prisma.run.groupBy({
      by: ['userId'],
      where,
      _max: { score: true },
      having: { score: { _max: { gt: 0 } } },
      orderBy: { _max: { score: 'desc' } },
      take: TOP_N,
    });
    const users = await prisma.user.findMany({ where: { id: { in: top.map((r) => r.userId) } }, select: { id: true, displayName: true, character: true } });
    const mine = await prisma.run.aggregate({ where: { ...where, userId: me }, _max: { score: true } });
    const myScore = mine._max.score ?? 0;
    let myRank: number | null = null;
    if (myScore > 0) {
      const scope = period === 'challenge'
        ? Prisma.sql`r.mode = 'challenge' AND r."challengeWeek" = ${isoWeekKey()}`
        : Prisma.sql`r.mode = 'normal' AND r."finishedAt" >= ${since}`;
      const rows = await prisma.$queryRaw<{ ahead: bigint }[]>`
        SELECT count(*) AS ahead FROM (
          SELECT r."userId" FROM "Run" r JOIN "User" u ON u.id = r."userId"
          WHERE r.status = 'FINISHED' AND u.flagged = false AND ${scope}
          GROUP BY r."userId" HAVING max(r.score) > ${myScore}
        ) t`;
      myRank = Number(rows[0]?.ahead ?? 0) + 1;
    }
    return {
      period,
      since: since.toISOString(),
      entries: top.map((r, i) => {
        const u = users.find((x) => x.id === r.userId);
        return { rank: i + 1, name: u?.displayName ?? '?', score: r._max.score ?? 0, character: u?.character ?? 'char_fox', me: r.userId === me };
      }),
      myRank,
      myScore,
    };
  });
}
