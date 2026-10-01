import { z } from 'zod';

const Env = z.object({
  DATABASE_URL: z.string().min(1),
  REDIS_URL: z.string().optional(),
  JWT_SECRET: z.string().min(32, 'JWT_SECRET must be at least 32 characters'),
  CORS_ORIGINS: z.string().default('http://localhost:5173'),
  PORT: z.coerce.number().int().default(3000),
  DAILY_CREDIT_LIABILITY_LIMIT: z.coerce.number().int().positive().default(5_000_000),
  RATE_LIMIT_PER_MIN: z.coerce.number().int().positive().default(120),
  RUN_RATE_LIMIT_PER_MIN: z.coerce.number().int().positive().default(30),
  AUTH_RATE_LIMIT_PER_MIN: z.coerce.number().int().positive().default(10),
  GUEST_ACCOUNTS_PER_IP_PER_DAY: z.coerce.number().int().positive().default(20),
  /** Only enable behind a reverse proxy you control; otherwise X-Forwarded-For lets clients spoof their IP. */
  /** 'false' (default), 'true', or a comma-separated list of trusted proxy IPs/CIDRs (preferred). */
  TRUST_PROXY: z.string().default('false').transform((v): boolean | string => (v === 'true' ? true : v === 'false' ? false : v)),
  /** Bearer token for GET /metrics. Without it the endpoint is only open outside production. */
  METRICS_TOKEN: z.string().min(16).optional(),
  /** Proof-of-work difficulty (leading zero bits) for creating/logging in guest accounts. */
  POW_BITS: z.coerce.number().int().min(0).max(24).default(16),
  /** Firebase service-account JSON for FCM push. Unset → push disabled (tokens are still stored). */
  FCM_SERVICE_ACCOUNT: z.string().optional(),
  FCM_ENDPOINT: z.string().url().default('https://fcm.googleapis.com'),
  /** UTC hour at which the opt-in daily reminder push goes out (16 = 19:00 in Türkiye). */
  PUSH_REMINDER_HOUR_UTC: z.coerce.number().int().min(0).max(23).default(16),
  /** Secret mixed into guest device-id hashes. Keep it stable: rotating it orphans guest accounts. Falls back to JWT_SECRET. */
  DEVICE_ID_PEPPER: z.string().min(32).optional(),
  NODE_ENV: z.string().default('development'),
});

export type Config = z.infer<typeof Env>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = Env.safeParse(env);
  if (!parsed.success) {
    throw new Error(`Invalid environment: ${parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join(', ')}`);
  }
  return parsed.data;
}
