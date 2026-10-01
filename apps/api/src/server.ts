import { PrismaClient } from '@prisma/client';
import { Redis } from 'ioredis';
import { buildApp } from './app';
import { loadConfig } from './config';
import { FcmSender, runDailyReminders } from './push';

const config = loadConfig();
const prisma = new PrismaClient();
const redis = config.REDIS_URL ? new Redis(config.REDIS_URL, { maxRetriesPerRequest: 1, enableOfflineQueue: false }) : undefined;
const app = await buildApp({ prisma, config, redis });

// Opt-in daily reminder push: checked every 10 minutes, sent once per device per day at the configured hour.
const fcm = FcmSender.fromEnv(config.FCM_SERVICE_ACCOUNT, config.FCM_ENDPOINT);
const reminderTimer = fcm ? setInterval(() => {
  if (new Date().getUTCHours() !== config.PUSH_REMINDER_HOUR_UTC) return;
  runDailyReminders(prisma, fcm).then((n) => { if (n) app.log.info({ sent: n }, 'daily reminder push'); }, (err) => app.log.error(err, 'reminder push failed'));
}, 10 * 60_000) : undefined;
if (!fcm) app.log.info('FCM_SERVICE_ACCOUNT not set: push notifications disabled');

const shutdown = async (): Promise<void> => {
  clearInterval(reminderTimer);
  await app.close();
  await prisma.$disconnect();
  redis?.disconnect();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

await app.listen({ port: config.PORT, host: '0.0.0.0' });
