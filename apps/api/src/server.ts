import { PrismaClient } from '@prisma/client';
import { Redis } from 'ioredis';
import { buildApp } from './app';
import { loadConfig } from './config';

const config = loadConfig();
const prisma = new PrismaClient();
const redis = config.REDIS_URL ? new Redis(config.REDIS_URL, { maxRetriesPerRequest: 1, enableOfflineQueue: false }) : undefined;
const app = await buildApp({ prisma, config, redis });

const shutdown = async (): Promise<void> => {
  await app.close();
  await prisma.$disconnect();
  redis?.disconnect();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

await app.listen({ port: config.PORT, host: '0.0.0.0' });
