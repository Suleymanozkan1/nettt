import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { createServer, type Server } from 'node:http';
import { createVerify, generateKeyPairSync } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import { utcDay } from '@stage/shared';
import { FcmSender, runDailyReminders } from '../src/push';
import { guest, makeApp, prisma, resetDb } from './helpers';

let app: FastifyInstance;
let fcm: Server;
let base = '';
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const sent: { auth: string; body: { message: { token: string; notification: { title: string } } } }[] = [];
const DEAD = 'dead-token-0000000000000000';
const BAD_PAYLOAD = 'bad-payload-00000000000000';
const FLAKY = 'flaky-token-00000000000000';

beforeAll(async () => {
  app = await makeApp();
  // Mock Google OAuth token endpoint + FCM v1 send endpoint.
  fcm = createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      if (req.url === '/token') {
        const assertion = new URLSearchParams(raw).get('assertion') ?? '';
        const [h, p, sig] = assertion.split('.');
        const valid = createVerify('RSA-SHA256').update(`${h}.${p}`).verify(publicKey, Buffer.from(sig ?? '', 'base64url'));
        const claims = JSON.parse(Buffer.from(p ?? '', 'base64url').toString());
        if (!valid || claims.scope !== 'https://www.googleapis.com/auth/firebase.messaging') { res.writeHead(401).end(); return; }
        res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ access_token: 'ya29.mock', expires_in: 3600 }));
        return;
      }
      if (req.url === '/v1/projects/golge-test/messages:send') {
        const body = JSON.parse(raw);
        sent.push({ auth: String(req.headers.authorization), body });
        if (body.message.token === DEAD) { res.writeHead(404).end('{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}'); return; }
        if (body.message.token === BAD_PAYLOAD) { res.writeHead(400).end('{"error":{"status":"INVALID_ARGUMENT"}}'); return; }
        if (body.message.token === FLAKY) { res.writeHead(503).end(); return; }
        res.writeHead(200, { 'content-type': 'application/json' }).end('{"name":"projects/golge-test/messages/1"}');
        return;
      }
      res.writeHead(404).end();
    });
  });
  await new Promise<void>((r) => fcm.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${(fcm.address() as AddressInfo).port}`;
});
afterAll(async () => { await app.close(); fcm.close(); });
beforeEach(async () => { await resetDb(); sent.length = 0; });

const sender = () => new FcmSender({ project_id: 'golge-test', client_email: 'push@golge-test.iam.gserviceaccount.com', private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(), token_uri: `${base}/token` }, base);

async function register(headers: Record<string, string>, token: string) {
  return app.inject({ method: 'POST', url: '/me/push-token', headers, payload: { token, platform: 'android' } });
}

describe('push notifications (FCM)', () => {
  it('registers device tokens; logout-all forgets them', async () => {
    const g = await guest(app);
    expect((await register(g.headers, 'short')).statusCode).toBe(400);
    expect((await register(g.headers, 'tok-aaaaaaaaaaaaaaaaaaaa')).statusCode).toBe(200);
    expect((await register(g.headers, 'tok-aaaaaaaaaaaaaaaaaaaa')).statusCode).toBe(200); // idempotent
    expect(await prisma.pushToken.count({ where: { userId: g.id } })).toBe(1);
    await app.inject({ method: 'POST', url: '/auth/logout-all', headers: g.headers });
    expect(await prisma.pushToken.count()).toBe(0);
  });

  it('daily reminder: signed OAuth JWT, opt-in only, once per day, dead tokens removed', async () => {
    const on = await guest(app);
    const off = await guest(app);
    const claimed = await guest(app);
    const dead = await guest(app);
    for (const g of [on, claimed, dead]) await app.inject({ method: 'PATCH', url: '/me/settings', headers: g.headers, payload: { notifications: true } });
    await register(on.headers, 'tok-on-00000000000000000');
    await register(off.headers, 'tok-off-0000000000000000');
    await register(claimed.headers, 'tok-claimed-000000000000');
    await register(dead.headers, DEAD);
    await app.inject({ method: 'POST', url: '/daily/claim', headers: claimed.headers });

    const n = await runDailyReminders(prisma, sender());
    expect(n).toBe(1);
    expect(sent.map((s) => s.body.message.token).sort()).toEqual([DEAD, 'tok-on-00000000000000000'].sort());
    expect(sent[0]!.auth).toBe('Bearer ya29.mock');
    expect(sent[0]!.body.message.notification.title).toBe('Gölge Kuklacı');
    expect(await prisma.pushToken.findUnique({ where: { token: DEAD } })).toBeNull();
    expect((await prisma.pushToken.findUnique({ where: { token: 'tok-on-00000000000000000' } }))!.lastSentDay).toBe(utcDay());

    // Second run the same day sends nothing.
    sent.length = 0;
    expect(await runDailyReminders(prisma, sender())).toBe(0);
    expect(sent).toHaveLength(0);
  });

  it('keeps tokens on payload errors and retries after transient failures', async () => {
    const a = await guest(app);
    const b = await guest(app);
    for (const g of [a, b]) await app.inject({ method: 'PATCH', url: '/me/settings', headers: g.headers, payload: { notifications: true } });
    await register(a.headers, BAD_PAYLOAD);
    await register(b.headers, FLAKY);
    expect(await runDailyReminders(prisma, sender())).toBe(0);
    expect(await prisma.pushToken.count()).toBe(2); // a 400 without UNREGISTERED is not a dead token
    expect((await prisma.pushToken.findUnique({ where: { token: FLAKY } }))!.lastSentDay).toBeNull(); // will retry
    sent.length = 0;
    await runDailyReminders(prisma, sender());
    expect(sent.map((s) => s.body.message.token)).toContain(FLAKY);
  });

  it('is disabled without a service account', () => {
    expect(FcmSender.fromEnv(undefined)).toBeNull();
    expect(() => FcmSender.fromEnv('{"project_id":"x"}')).toThrow(/missing/);
  });
});
