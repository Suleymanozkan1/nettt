import type { FastifyInstance } from 'fastify';
import { randomInt } from 'node:crypto';
import type { Prisma } from '@prisma/client';
import {
  FinishRunBody, MAX_RUN_MS, REVIVE_GEM_COST, replayRun, computeRunRewards, dailyMissions, applyRunToMission,
  isCumulative, utcDay, type RunParams,
} from '@stage/shared';
import { HttpError, parse } from '../errors';
import { grant, rewardsPaused, spend } from '../ledger';
import { track, userId } from '../app';

/** Allowed difference between the run's simulated duration and real elapsed wall-clock time. */
const CLOCK_SLACK_MS = 3000;
const REJECTIONS_BEFORE_FLAG = 3;

export async function runRoutes(app: FastifyInstance): Promise<void> {
  const { prisma, config } = app.deps;
  const limited = { onRequest: [app.authenticate], config: { rateLimit: { max: config.RUN_RATE_LIMIT_PER_MIN, timeWindow: '1 minute' } } };

  app.post('/runs', limited, async (req) => {
    const uid = userId(req);
    // Session cleanup: shows the player abandoned (app closed, never submitted) are closed without rewards.
    await prisma.run.updateMany({
      where: { userId: uid, status: 'STARTED', startedAt: { lt: new Date(Date.now() - MAX_RUN_MS - 60_000) } },
      data: { status: 'REJECTED', rejectReason: 'abandoned', finishedAt: new Date() },
    });
    const upgrades = await prisma.upgradeLevel.findMany({ where: { userId: uid } });
    const params: RunParams = {
      toleranceLevel: upgrades.find((u) => u.upgradeId === 'tolerance')?.level ?? 0,
      encoreLevel: upgrades.find((u) => u.upgradeId === 'encore')?.level ?? 0,
    };
    const run = await prisma.run.create({ data: { userId: uid, seed: randomInt(0, 2 ** 31), params: params as unknown as Prisma.InputJsonValue } });
    await track(prisma, uid, 'run_start', { runId: run.id });
    return { runId: run.id, seed: run.seed, params };
  });

  app.post<{ Params: { id: string } }>('/runs/:id/finish', limited, async (req, reply) => {
    const uid = userId(req);
    const { inputs } = parse(FinishRunBody, req.body);
    const run = await prisma.run.findFirst({ where: { id: req.params.id, userId: uid } });
    if (!run) throw new HttpError(404, 'run_not_found');
    if (run.status !== 'STARTED') throw new HttpError(409, 'run_already_submitted');

    const elapsed = Date.now() - run.startedAt.getTime();
    const replay = replayRun(run.seed, run.params as unknown as RunParams, inputs);
    let reason: string | null = null;
    if (elapsed > MAX_RUN_MS + 60_000) reason = 'expired';
    else if (!replay.ok) reason = replay.reason;
    else if (replay.summary.durationMs > elapsed + CLOCK_SLACK_MS) reason = 'faster_than_real_time';

    if (reason || !replay.ok) {
      await reject(run.id, uid, reason ?? 'invalid');
      return reply.status(422).send({ error: 'run_rejected', reason });
    }
    const summary = replay.summary;
    const paused = await rewardsPaused(prisma, config.DAILY_CREDIT_LIABILITY_LIMIT);
    const rewards = computeRunRewards(summary);
    const day = utcDay();

    try {
      const result = await prisma.$transaction(async (tx) => {
        // Status flip is the replay lock: only one request can move STARTED → FINISHED.
        const flipped = await tx.run.updateMany({
          where: { id: run.id, status: 'STARTED' },
          data: { status: 'FINISHED', finishedAt: new Date(), ...summary },
        });
        if (flipped.count !== 1) throw new HttpError(409, 'run_already_submitted');
        if (summary.revives > 0) await spend(tx, uid, 'gems', REVIVE_GEM_COST * summary.revives, 'revive', run.id);

        await tx.user.update({ where: { id: uid }, data: { fans: { increment: rewards.fans }, totalRuns: { increment: 1 } } });
        // Conditional write: concurrent finishes can only ever raise the best score.
        const newBest = (await tx.user.updateMany({ where: { id: uid, bestScore: { lt: summary.score } }, data: { bestScore: summary.score } })).count === 1;
        const credits = paused ? 0 : await grant(tx, uid, 'credits', rewards.credits, 'run', run.id);
        const gems = paused ? 0 : await grant(tx, uid, 'gems', rewards.gems, 'run', run.id);

        const missions = [];
        for (const m of dailyMissions(uid, day)) {
          const row = await tx.missionProgress.upsert({
            where: { userId_day_missionKey: { userId: uid, day, missionKey: m.key } },
            create: { userId: uid, day, missionKey: m.key, progress: 0 },
            update: {},
          });
          // Atomic updates so concurrent finishes never lose progress.
          const value = applyRunToMission(m, 0, summary);
          if (isCumulative(m.kind)) await tx.missionProgress.update({ where: { id: row.id }, data: { progress: { increment: value } } });
          else await tx.missionProgress.updateMany({ where: { id: row.id, progress: { lt: value } }, data: { progress: value } });
          const { progress } = await tx.missionProgress.findUniqueOrThrow({ where: { id: row.id }, select: { progress: true } });
          missions.push({ key: m.key, label: m.label, progress: Math.min(progress, m.target), target: m.target, completed: progress >= m.target });
        }
        await track(tx, uid, 'run_finish', { runId: run.id, ...summary, credits, gems });
        return { credits, gems, newBest, missions };
      });
      return { verified: true, summary, rewards: { fans: rewards.fans, credits: result.credits, gems: result.gems }, rewardsPaused: paused, newBest: result.newBest, missions: result.missions };
    } catch (err) {
      if (err instanceof HttpError && err.code === 'insufficient_funds') {
        await reject(run.id, uid, 'revive_unpaid');
        return reply.status(422).send({ error: 'run_rejected', reason: 'revive_unpaid' });
      }
      throw err;
    }
  });

  async function reject(runId: string, uid: string, reason: string): Promise<void> {
    await prisma.run.updateMany({ where: { id: runId, status: 'STARTED' }, data: { status: 'REJECTED', rejectReason: reason, finishedAt: new Date() } });
    await track(prisma, uid, 'run_rejected', { runId, reason });
    const recent = await prisma.run.count({ where: { userId: uid, status: 'REJECTED', rejectReason: { not: 'abandoned' }, finishedAt: { gte: new Date(Date.now() - 86_400_000) } } });
    if (recent >= REJECTIONS_BEFORE_FLAG) await prisma.user.update({ where: { id: uid }, data: { flagged: true } });
  }
}
