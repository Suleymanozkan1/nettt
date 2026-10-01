import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import type { Server } from '@colyseus/core';
import { Client } from 'colyseus.js';
import { Redis } from 'ioredis';
import { startRealtime } from '../src/realtime-server';
import { loadConfig } from '../src/config';
import { guest, makeApp, prisma, resetDb } from './helpers';

const REDIS_URL = process.env.TEST_REDIS_URL ?? 'redis://localhost:6379/7';
let app: FastifyInstance;
const nodes: Server[] = [];

beforeAll(async () => {
  await new Redis(REDIS_URL).flushdb().catch(() => undefined);
  app = await makeApp();
  await resetDb();
  const config = loadConfig({ ...process.env, JWT_SECRET: 'test-secret-test-secret-test-secret-1234' });
  for (const port of [25681, 25682]) nodes.push(await startRealtime(port, { prisma, config }, { redisUrl: REDIS_URL, publicAddress: `localhost:${port}` }));
});
afterAll(async () => { for (const n of nodes) await n.gracefullyShutdown(false); await app.close(); });

describe('realtime cluster (RedisPresence + RedisDriver)', () => {
  it('two players matchmaking through different nodes land in the same duel', async () => {
    const a = await guest(app); const b = await guest(app);
    const ca = new Client('ws://localhost:25681'); ca.auth.token = a.token;
    const cb = new Client('ws://localhost:25682'); cb.auth.token = b.token;
    const ra = await ca.joinOrCreate('duel');
    const rb = await cb.joinOrCreate('duel');
    expect(rb.roomId).toBe(ra.roomId);
    await new Promise((r) => setTimeout(r, 300));
    expect((ra.state as { players: Map<string, unknown> }).players.size).toBe(2);
    await Promise.all([ra.leave(), rb.leave()]);
  }, 30000);
});
