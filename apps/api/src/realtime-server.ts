import { createServer } from 'node:http';
import { PrismaClient } from '@prisma/client';
import { Server } from 'colyseus';
import { WebSocketTransport } from '@colyseus/ws-transport';
import { loadConfig } from './config';
import { DuelRoom } from './realtime/DuelRoom';
import { registry } from './metrics';

/** Colyseus realtime server for live duels (separate process from the REST API). */
export async function startRealtime(port: number, deps = { prisma: new PrismaClient(), config: loadConfig() }): Promise<Server> {
  DuelRoom.deps = deps;
  const http = createServer(async (req, res) => {
    if (req.url === '/health') { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"ok":true}'); return; }
    const metricsAllowed = deps.config.METRICS_TOKEN ? req.headers.authorization === `Bearer ${deps.config.METRICS_TOKEN}` : deps.config.NODE_ENV !== 'production';
    if (req.url === '/metrics' && metricsAllowed) { res.writeHead(200, { 'content-type': registry.contentType }); res.end(await registry.metrics()); return; }
    res.writeHead(404); res.end();
  });
  const server = new Server({ transport: new WebSocketTransport({ server: http }) });
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
