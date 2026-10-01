import { createServer } from 'node:http';
import { PrismaClient } from '@prisma/client';
import { Server } from '@colyseus/core';
import { WebSocketTransport } from '@colyseus/ws-transport';
import { RedisPresence } from '@colyseus/redis-presence';
import { RedisDriver } from '@colyseus/redis-driver';
import { loadConfig } from './config';
import { DuelRoom } from './realtime/DuelRoom';
import { registry } from './metrics';

/** Colyseus realtime server for live duels (separate process from the REST API). */
/**
 * With REDIS_URL set, rooms and matchmaking are shared through Redis (RedisPresence + RedisDriver), so several
 * realtime processes behind a load balancer form one pool; `publicAddress` tells clients which node owns a room.
 */
export async function startRealtime(
  port: number,
  deps = { prisma: new PrismaClient(), config: loadConfig() },
  cluster: { redisUrl?: string; publicAddress?: string } = { redisUrl: process.env.REDIS_URL, publicAddress: process.env.REALTIME_PUBLIC_ADDRESS },
): Promise<Server> {
  DuelRoom.deps = deps;
  const http = createServer(async (req, res) => {
    if (req.url === '/health') { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"ok":true}'); return; }
    const metricsAllowed = deps.config.METRICS_TOKEN ? req.headers.authorization === `Bearer ${deps.config.METRICS_TOKEN}` : deps.config.NODE_ENV !== 'production';
    if (req.url === '/metrics' && metricsAllowed) { res.writeHead(200, { 'content-type': registry.contentType }); res.end(await registry.metrics()); return; }
    // Colyseus' own listener on this server answers /matchmake; everything else gets a 404 instead of hanging.
    if (!req.url?.startsWith('/matchmake')) { res.writeHead(404); res.end(); }
  });
  const server = new Server({
    transport: new WebSocketTransport({ server: http }),
    ...(cluster.redisUrl ? { presence: new RedisPresence(cluster.redisUrl), driver: new RedisDriver(cluster.redisUrl) } : {}),
    ...(cluster.publicAddress ? { publicAddress: cluster.publicAddress } : {}),
  });
  server.define('duel', DuelRoom);
  await server.listen(port);
  return server;
}

if (process.argv[1]?.endsWith('realtime-server.ts')) {
  const port = Number(process.env.REALTIME_PORT ?? 2567);
  const server = await startRealtime(port);
  const stop = async () => { await server.gracefullyShutdown(false); process.exit(0); };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}
