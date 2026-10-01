import { createSign } from 'node:crypto';
import type { PrismaClient } from '@prisma/client';
import { DEFAULT_SETTINGS, utcDay, type Settings } from '@stage/shared';
import { metrics } from './metrics';

/** Firebase service-account JSON (Project settings → Service accounts → Generate new private key). */
export interface ServiceAccount { project_id: string; client_email: string; private_key: string; token_uri?: string }
export type SendResult = 'ok' | 'invalid_token' | 'error';

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const b64url = (b: Buffer | string) => Buffer.from(b).toString('base64url');

/**
 * Minimal FCM HTTP v1 sender: OAuth2 service-account JWT (RS256) → access token → messages:send.
 * Android and iOS (APNs key uploaded to Firebase) are both delivered through FCM.
 */
export class FcmSender {
  private access: { token: string; exp: number } | null = null;

  constructor(private readonly sa: ServiceAccount, private readonly endpoint = 'https://fcm.googleapis.com') {}

  static fromEnv(json: string | undefined, endpoint?: string): FcmSender | null {
    if (!json) return null;
    const sa = JSON.parse(json) as ServiceAccount;
    if (!sa.project_id || !sa.client_email || !sa.private_key) throw new Error('FCM_SERVICE_ACCOUNT is missing project_id/client_email/private_key');
    return new FcmSender(sa, endpoint);
  }

  private async accessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (this.access && this.access.exp - 60 > now) return this.access.token;
    const aud = this.sa.token_uri ?? 'https://oauth2.googleapis.com/token';
    const unsigned = `${b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${b64url(JSON.stringify({ iss: this.sa.client_email, scope: SCOPE, aud, iat: now, exp: now + 3600 }))}`;
    const signature = createSign('RSA-SHA256').update(unsigned).sign(this.sa.private_key);
    const res = await fetch(aud, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${b64url(signature)}` }),
    });
    if (!res.ok) throw new Error(`FCM auth failed: ${res.status}`);
    const data = (await res.json()) as { access_token: string; expires_in: number };
    this.access = { token: data.access_token, exp: now + data.expires_in };
    return data.access_token;
  }

  async send(token: string, title: string, body: string): Promise<SendResult> {
    try {
      const res = await fetch(`${this.endpoint}/v1/projects/${this.sa.project_id}/messages:send`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${await this.accessToken()}` },
        body: JSON.stringify({ message: { token, notification: { title, body }, android: { priority: 'high' } } }),
      });
      // 404 UNREGISTERED / 400 INVALID_ARGUMENT: the token is dead and should be forgotten.
      const result: SendResult = res.ok ? 'ok' : res.status === 404 || res.status === 400 ? 'invalid_token' : 'error';
      metrics.pushSent.inc({ result });
      return result;
    } catch {
      metrics.pushSent.inc({ result: 'error' });
      return 'error';
    }
  }
}

/**
 * Daily reminder push (opt-in): players with reminders on who have not claimed today's reward get at most one
 * push per day per device. Dead tokens are removed. Returns the number of pushes delivered.
 */
export async function runDailyReminders(prisma: PrismaClient, sender: FcmSender, day = utcDay()): Promise<number> {
  const tokens = await prisma.pushToken.findMany({
    where: { OR: [{ lastSentDay: null }, { lastSentDay: { not: day } }] },
    include: { user: { select: { settings: true, daily: { select: { lastClaimDay: true } } } } },
  });
  let sent = 0;
  for (const t of tokens) {
    const settings = { ...DEFAULT_SETTINGS, ...(t.user.settings as Partial<Settings>) };
    if (!settings.notifications || t.user.daily?.lastClaimDay === day) continue;
    // Claim the slot first so concurrent runners never double-send.
    const claimed = await prisma.pushToken.updateMany({ where: { id: t.id, OR: [{ lastSentDay: null }, { lastSentDay: { not: day } }] }, data: { lastSentDay: day } });
    if (claimed.count !== 1) continue;
    const r = await sender.send(t.token, 'Gölge Kuklacı', 'Günlük ödülün ve yeni görevlerin hazır. Perde açılıyor!');
    if (r === 'ok') sent++;
    else if (r === 'invalid_token') await prisma.pushToken.deleteMany({ where: { id: t.id } });
  }
  return sent;
}
