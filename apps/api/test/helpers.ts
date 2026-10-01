import { PrismaClient } from '@prisma/client';
import type { FastifyInstance } from 'fastify';
import { randomBytes } from 'node:crypto';
import { RunSim, type RunInput, type RunParams } from '@stage/shared';
import { buildApp } from '../src/app';
import { loadConfig } from '../src/config';
import { clearBreakerCache } from '../src/ledger';

export const prisma = new PrismaClient();

export async function makeApp(env: Record<string, string> = {}): Promise<FastifyInstance> {
  clearBreakerCache();
  const config = loadConfig({ ...process.env, JWT_SECRET: 'test-secret-test-secret-test-secret-1234', CORS_ORIGINS: 'http://localhost:5173', AUTH_RATE_LIMIT_PER_MIN: '10000', ...env });
  return buildApp({ prisma, config, logger: false });
}

export async function resetDb(): Promise<void> {
  await prisma.$executeRawUnsafe('TRUNCATE "Event","Transaction","InventoryItem","UpgradeLevel","DailyState","MissionProgress","Run","User","EconomyConfig" CASCADE');
  clearBreakerCache();
}

export async function guest(app: FastifyInstance): Promise<{ token: string; headers: Record<string, string>; id: string }> {
  const res = await app.inject({ method: 'POST', url: '/auth/guest', payload: { deviceId: randomBytes(16).toString('hex') } });
  const token = res.json().token as string;
  const headers = { authorization: `Bearer ${token}` };
  const me = await app.inject({ method: 'GET', url: '/me', headers });
  return { token, headers, id: me.json().id };
}

/**
 * Plays like a skilled human: `perfect` best-timed taps, then stops tapping so the remaining rounds time out.
 * Returns the inputs and the simulated show length (ms from run start).
 */
export function playInputs(seed: number, params: RunParams, perfect: number, opts: { revive?: boolean } = {}): { inputs: RunInput[]; durationMs: number } {
  const sim = new RunSim(seed, params);
  const inputs: RunInput[] = [];
  let t = 0;
  for (let i = 0; i < perfect; i++) {
    while (!sim.canTap(t)) t++;
    let best = t; let bestErr = Infinity;
    for (let x = t; sim.canTap(x); x++) {
      const v = sim.view(x);
      const e = Math.hypot((v.shadowX - v.holeX) / 60, (v.shadowScale - v.holeScale) / 0.35);
      if (e < bestErr) { bestErr = e; best = x; }
    }
    t = best;
    sim.tap(t); inputs.push({ t, k: 'tap' });
  }
  sim.advance(10_000_000);
  if (opts.revive) {
    t = sim.deathT + 200;
    sim.revive(t); inputs.push({ t, k: 'revive' });
    sim.advance(10_000_000);
  }
  return { inputs, durationMs: sim.summary().durationMs };
}

/** Backdates a run so its real elapsed time covers the simulated duration. */
export async function backdateRun(runId: string, ms: number): Promise<void> {
  await prisma.run.update({ where: { id: runId }, data: { startedAt: new Date(Date.now() - ms) } });
}
