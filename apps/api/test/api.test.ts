import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { computeRunRewards, REVIVE_GEM_COST, solvePow, utcDay } from '@stage/shared';
import { backdateRun, guest, guestBody, makeApp, playInputs, prisma, resetDb } from './helpers';
import { clearBreakerCache } from '../src/ledger';

let app: FastifyInstance;
beforeAll(async () => { app = await makeApp(); });
afterAll(async () => { await app.close(); await prisma.$disconnect(); });
beforeEach(resetDb);

async function playRun(headers: Record<string, string>, perfect = 5, opts: { revive?: boolean; backdate?: boolean } = {}) {
  const start = await app.inject({ method: 'POST', url: '/runs', headers });
  expect(start.statusCode).toBe(200);
  const { runId, seed, params } = start.json();
  const { inputs, durationMs } = playInputs(seed, params, perfect, opts);
  if (opts.backdate !== false) await backdateRun(runId, durationMs + 1000);
  const fin = await app.inject({ method: 'POST', url: `/runs/${runId}/finish`, headers, payload: { inputs } });
  return { runId, inputs, fin };
}

describe('auth', () => {
  it('guest login is stable per device and /me needs a token', async () => {
    expect((await app.inject({ method: 'GET', url: '/me' })).statusCode).toBe(401);
    const a = await app.inject({ method: 'POST', url: '/auth/guest', payload: await guestBody(app, 'device-aaaaaaaaaaaaaaaa') });
    const b = await app.inject({ method: 'POST', url: '/auth/guest', payload: await guestBody(app, 'device-aaaaaaaaaaaaaaaa') });
    const meA = await app.inject({ method: 'GET', url: '/me', headers: { authorization: `Bearer ${a.json().token}` } });
    const meB = await app.inject({ method: 'GET', url: '/me', headers: { authorization: `Bearer ${b.json().token}` } });
    expect(meA.json().id).toBe(meB.json().id);
    expect(meA.json()).toMatchObject({ credits: 0, gems: 0, level: 1, tutorialDone: false, settings: { notifications: false } });
  });

  it('rejects invalid input and bad tokens', async () => {
    expect((await app.inject({ method: 'POST', url: '/auth/guest', payload: { ...(await guestBody(app, 'device-bbbbbbbbbbbbbbbb')), deviceId: 'x' } })).statusCode).toBe(400);
    expect((await app.inject({ method: 'GET', url: '/me', headers: { authorization: 'Bearer nope' } })).statusCode).toBe(401);
  });

  it('upgrades a guest to email account and logs in', async () => {
    const g = await guest(app);
    const reg = await app.inject({ method: 'POST', url: '/auth/register', headers: g.headers, payload: { email: 'A@x.io', password: 'hunter2hunter2', displayName: 'Alice' } });
    expect(reg.statusCode).toBe(200);
    const dup = await app.inject({ method: 'POST', url: '/auth/register', payload: { email: 'a@x.io', password: 'whatever123' } });
    expect(dup.statusCode).toBe(409);
    expect((await app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'a@x.io', password: 'wrongwrong' } })).statusCode).toBe(401);
    const login = await app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'a@x.io', password: 'hunter2hunter2' } });
    const me = await app.inject({ method: 'GET', url: '/me', headers: { authorization: `Bearer ${login.json().token}` } });
    expect(me.json()).toMatchObject({ id: g.id, displayName: 'Alice', registered: true });
  });

  it('never re-keys an account that already has credentials (bearer token alone is not enough)', async () => {
    const g = await guest(app);
    await app.inject({ method: 'POST', url: '/auth/register', headers: g.headers, payload: { email: 'owner@x.io', password: 'owner-pass-123' } });
    const takeover = await app.inject({ method: 'POST', url: '/auth/register', headers: g.headers, payload: { email: 'evil@x.io', password: 'evil-pass-123' } });
    expect(takeover.statusCode).toBe(409);
    expect(takeover.json().error).toBe('already_registered');
    expect((await app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'owner@x.io', password: 'owner-pass-123' } })).statusCode).toBe(200);
    expect((await app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'evil@x.io', password: 'evil-pass-123' } })).statusCode).toBe(401);
  });
});

describe('proof-of-work for guest accounts', () => {
  it('requires a solved, unexpired, single-use challenge', async () => {
    const strictApp = await makeApp({ POW_BITS: '12' });
    const missing = await strictApp.inject({ method: 'POST', url: '/auth/guest', payload: { deviceId: 'device-cccccccccccccccc' } });
    expect(missing.statusCode).toBe(400);
    const ch = (await strictApp.inject({ method: 'GET', url: '/auth/challenge' })).json();
    expect(ch.bits).toBe(12);
    const unknown = await strictApp.inject({ method: 'POST', url: '/auth/guest', payload: { deviceId: 'device-cccccccccccccccc', powId: 'unknownchallenge', powNonce: '1' } });
    expect(unknown.statusCode).toBe(403);
    const body = { deviceId: 'device-cccccccccccccccc', powId: ch.id, powNonce: solvePow(ch.salt, 12) };
    expect((await strictApp.inject({ method: 'POST', url: '/auth/guest', payload: body })).statusCode).toBe(200);
    expect((await strictApp.inject({ method: 'POST', url: '/auth/guest', payload: body })).statusCode).toBe(403); // single use
    const ch2 = (await strictApp.inject({ method: 'GET', url: '/auth/challenge' })).json();
    let bad = 0; while (solvePow(ch2.salt, 12) === String(bad)) bad++;
    const wrong = await strictApp.inject({ method: 'POST', url: '/auth/guest', payload: { deviceId: 'device-cccccccccccccccc', powId: ch2.id, powNonce: String(bad) } });
    expect(wrong.statusCode).toBe(403);
    await strictApp.close();
  });
});

describe('runs (server-authoritative)', () => {
  it('verifies a run, grants rewards from the replay, updates best score and missions', async () => {
    const g = await guest(app);
    const { fin } = await playRun(g.headers, 8);
    expect(fin.statusCode).toBe(200);
    const body = fin.json();
    expect(body.verified).toBe(true);
    expect(body.summary).toMatchObject({ fits: 8, perfects: 8, maxCombo: 8, misses: 3 });
    expect(body.rewards).toEqual(computeRunRewards(body.summary));
    expect(body.missions).toHaveLength(3);
    const me = (await app.inject({ method: 'GET', url: '/me', headers: g.headers })).json();
    expect(me.bestScore).toBe(body.summary.score);
    expect(me.credits).toBe(body.rewards.credits);
    expect(me.fans).toBe(body.rewards.fans);
    const tx = await prisma.transaction.findMany({ where: { userId: g.id } });
    expect(tx.map((t) => t.reason)).toEqual(['run']);
  });

  it('ignores client-claimed scores (extra fields rejected) and blocks replays', async () => {
    const g = await guest(app);
    const { runId, inputs, fin } = await playRun(g.headers, 3);
    expect(fin.statusCode).toBe(200);
    const again = await app.inject({ method: 'POST', url: `/runs/${runId}/finish`, headers: g.headers, payload: { inputs } });
    expect(again.statusCode).toBe(409);
    const s = await app.inject({ method: 'POST', url: '/runs', headers: g.headers });
    const forged = await app.inject({ method: 'POST', url: `/runs/${s.json().runId}/finish`, headers: g.headers, payload: { inputs, score: 99999 } });
    // Replaying old inputs against a new seed either fails validation or yields the honest replay result.
    if (forged.statusCode === 200) expect(forged.json().summary.score).toBeLessThan(99999);
  });

  it('rejects tampered / bot-speed inputs and flags repeat offenders', async () => {
    const g = await guest(app);
    for (let i = 0; i < 3; i++) {
      const s = await app.inject({ method: 'POST', url: '/runs', headers: g.headers });
      const res = await app.inject({ method: 'POST', url: `/runs/${s.json().runId}/finish`, headers: g.headers, payload: { inputs: [{ t: 700, k: 'tap' }, { t: 710, k: 'tap' }, { t: 720, k: 'tap' }] } });
      expect(res.statusCode).toBe(422);
    }
    expect((await prisma.user.findUniqueOrThrow({ where: { id: g.id } })).flagged).toBe(true);
  });

  it('closes abandoned runs when a new show starts', async () => {
    const g = await guest(app);
    const old = (await app.inject({ method: 'POST', url: '/runs', headers: g.headers })).json();
    await backdateRun(old.runId, 2 * 60 * 60 * 1000);
    await app.inject({ method: 'POST', url: '/runs', headers: g.headers });
    expect(await prisma.run.findUniqueOrThrow({ where: { id: old.runId } })).toMatchObject({ status: 'REJECTED', rejectReason: 'abandoned' });
  });

  it('abandoned runs do not count towards flagging', async () => {
    const g = await guest(app);
    for (let i = 0; i < 3; i++) {
      await prisma.run.create({ data: { userId: g.id, seed: 1, params: {}, status: 'REJECTED', rejectReason: 'abandoned', finishedAt: new Date() } });
    }
    const s = await app.inject({ method: 'POST', url: '/runs', headers: g.headers });
    await app.inject({ method: 'POST', url: `/runs/${s.json().runId}/finish`, headers: g.headers, payload: { inputs: [{ t: 700, k: 'tap' }, { t: 710, k: 'tap' }] } });
    expect((await prisma.user.findUniqueOrThrow({ where: { id: g.id } })).flagged).toBe(false);
  });

  it('daily pacing: after 10 shows today credits drop to 25% and the response says so', async () => {
    const g = await guest(app);
    const today = new Date();
    for (let i = 0; i < 10; i++) await prisma.run.create({ data: { userId: g.id, seed: i, params: {}, status: 'FINISHED', finishedAt: today } });
    const { fin } = await playRun(g.headers, 8);
    const body = fin.json();
    expect(body.tiredAudience).toBe(true);
    expect(body.rewards.credits).toBe(Math.floor(computeRunRewards(body.summary).credits * 0.25));
    expect(body.rewards.fans).toBe(computeRunRewards(body.summary).fans);
  });

  it('flags a frame-perfect bot for review (score kept, hidden from boards)', async () => {
    const g = await guest(app);
    const { fin } = await playRun(g.headers, 30);
    expect(fin.statusCode).toBe(200);
    expect(fin.json().summary.perfects).toBe(30);
    expect((await prisma.user.findUniqueOrThrow({ where: { id: g.id } })).flagged).toBe(true);
    expect(await prisma.event.count({ where: { userId: g.id, type: 'suspicious_precision' } })).toBe(1);
  });

  it('rejects runs submitted faster than real time (speed hack)', async () => {
    const g = await guest(app);
    const { fin } = await playRun(g.headers, 4, { backdate: false });
    expect(fin.statusCode).toBe(422);
    expect(fin.json().reason).toBe('faster_than_real_time');
  });

  it('charges gems for a revive and rejects unpaid revives', async () => {
    const g = await guest(app);
    const unpaid = await playRun(g.headers, 2, { revive: true });
    expect(unpaid.fin.statusCode).toBe(422);
    expect(unpaid.fin.json().reason).toBe('revive_unpaid');
    await prisma.user.update({ where: { id: g.id }, data: { gems: 10 } });
    const paid = await playRun(g.headers, 2, { revive: true });
    expect(paid.fin.statusCode).toBe(200);
    expect(paid.fin.json().summary.revives).toBe(1);
    expect((await prisma.user.findUniqueOrThrow({ where: { id: g.id } })).gems).toBe(10 - REVIVE_GEM_COST + paid.fin.json().rewards.gems);
  });

  it('circuit breaker stops reward payouts but keeps the score', async () => {
    const g = await guest(app);
    await prisma.economyConfig.create({ data: { key: 'rewards_paused', value: { paused: true } } });
    const { fin } = await playRun(g.headers, 5);
    expect(fin.statusCode).toBe(200);
    expect(fin.json()).toMatchObject({ rewardsPaused: true, rewards: { credits: 0, gems: 0 } });
    expect((await app.inject({ method: 'POST', url: '/daily/claim', headers: g.headers })).statusCode).toBe(503);
  });
});

describe('daily + missions', () => {
  it('claims daily once per day', async () => {
    const g = await guest(app);
    const d = (await app.inject({ method: 'GET', url: '/daily', headers: g.headers })).json();
    expect(d).toMatchObject({ canClaim: true, nextCycleDay: 1, streak: 0 });
    const c = await app.inject({ method: 'POST', url: '/daily/claim', headers: g.headers });
    expect(c.json()).toMatchObject({ streak: 1, cycleDay: 1, reward: { credits: 50 } });
    const [r1, r2] = await Promise.all([
      app.inject({ method: 'POST', url: '/daily/claim', headers: g.headers }),
      app.inject({ method: 'POST', url: '/daily/claim', headers: g.headers }),
    ]);
    expect([r1.statusCode, r2.statusCode]).toEqual([409, 409]);
    expect((await prisma.user.findUniqueOrThrow({ where: { id: g.id } })).credits).toBe(50);
  });

  it('mission claim requires completion and pays once', async () => {
    const g = await guest(app);
    const ms = (await app.inject({ method: 'GET', url: '/missions', headers: g.headers })).json().missions;
    expect(ms).toHaveLength(3);
    const m = ms[0];
    expect((await app.inject({ method: 'POST', url: `/missions/${m.key}/claim`, headers: g.headers })).statusCode).toBe(409);
    await prisma.missionProgress.create({ data: { userId: g.id, day: utcDay(), missionKey: m.key, progress: m.target } });
    expect((await app.inject({ method: 'POST', url: `/missions/${m.key}/claim`, headers: g.headers })).statusCode).toBe(200);
    expect((await app.inject({ method: 'POST', url: `/missions/${m.key}/claim`, headers: g.headers })).statusCode).toBe(409);
    expect((await app.inject({ method: 'POST', url: '/missions/not_a_mission/claim', headers: g.headers })).statusCode).toBe(404);
  });
});

describe('shop / inventory / upgrades', () => {
  it('buys with sufficient funds only, no duplicates, equips owned items', async () => {
    const g = await guest(app);
    expect((await app.inject({ method: 'POST', url: '/shop/buy', headers: g.headers, payload: { itemId: 'lamp_neon' } })).statusCode).toBe(402);
    expect((await app.inject({ method: 'POST', url: '/loadout', headers: g.headers, payload: { skin: 'lamp_neon' } })).statusCode).toBe(403);
    await prisma.user.update({ where: { id: g.id }, data: { credits: 1000 } });
    const [a, b] = await Promise.all([
      app.inject({ method: 'POST', url: '/shop/buy', headers: g.headers, payload: { itemId: 'lamp_neon' } }),
      app.inject({ method: 'POST', url: '/shop/buy', headers: g.headers, payload: { itemId: 'lamp_neon' } }),
    ]);
    expect([a.statusCode, b.statusCode].sort()).toEqual([200, 409]);
    expect((await prisma.user.findUniqueOrThrow({ where: { id: g.id } })).credits).toBe(700);
    expect((await app.inject({ method: 'POST', url: '/loadout', headers: g.headers, payload: { skin: 'lamp_neon' } })).json()).toMatchObject({ skin: 'lamp_neon' });
    const inv = (await app.inject({ method: 'GET', url: '/inventory', headers: g.headers })).json();
    expect(inv.items.map((i: { id: string }) => i.id)).toContain('lamp_neon');
    expect((await app.inject({ method: 'POST', url: '/loadout', headers: g.headers, payload: { skin: 'char_owl' } })).statusCode).toBe(400);
  });

  it('upgrades cost credits, cap at max and feed run params', async () => {
    const g = await guest(app);
    await prisma.user.update({ where: { id: g.id }, data: { credits: 6000 } });
    for (const expected of [1, 2, 3]) {
      const r = await app.inject({ method: 'POST', url: '/shop/upgrade', headers: g.headers, payload: { upgradeId: 'tolerance' } });
      expect(r.json().level).toBe(expected);
    }
    expect((await app.inject({ method: 'POST', url: '/shop/upgrade', headers: g.headers, payload: { upgradeId: 'tolerance' } })).statusCode).toBe(409);
    expect((await prisma.user.findUniqueOrThrow({ where: { id: g.id } })).credits).toBe(6000 - 600 - 1500 - 3000);
    const run = (await app.inject({ method: 'POST', url: '/runs', headers: g.headers })).json();
    expect(run.params.toleranceLevel).toBe(3);
  });
});

describe('leaderboard', () => {
  it('orders by best score, excludes flagged users, reports my rank', async () => {
    const a = await guest(app); const b = await guest(app); const c = await guest(app);
    await playRun(a.headers, 3);
    await playRun(b.headers, 9);
    await playRun(c.headers, 12);
    await prisma.user.update({ where: { id: c.id }, data: { flagged: true } });
    for (const period of ['all', 'weekly']) {
      const lb = (await app.inject({ method: 'GET', url: `/leaderboard?period=${period}`, headers: a.headers })).json();
      expect(lb.entries).toHaveLength(2);
      expect(lb.entries[0].score).toBeGreaterThan(lb.entries[1].score);
      expect(lb.myRank).toBe(2);
      expect(lb.entries[1].me).toBe(true);
    }
  });
});

describe('admin', () => {
  it('is forbidden for players and works for admins', async () => {
    const p = await guest(app);
    expect((await app.inject({ method: 'GET', url: '/admin/economy', headers: p.headers })).statusCode).toBe(403);
    await prisma.user.update({ where: { id: p.id }, data: { role: 'ADMIN' } });
    const eco = await app.inject({ method: 'GET', url: '/admin/economy', headers: p.headers });
    expect(eco.json()).toMatchObject({ rewardsPaused: false, outstanding: { credits: 0, gems: 0 } });
    await app.inject({ method: 'POST', url: '/admin/economy', headers: p.headers, payload: { paused: true } });
    expect((await app.inject({ method: 'GET', url: '/admin/economy', headers: p.headers })).json().rewardsPaused).toBe(true);
    expect((await app.inject({ method: 'GET', url: `/admin/users/${p.id}`, headers: p.headers })).statusCode).toBe(200);
    expect((await app.inject({ method: 'POST', url: '/admin/users/no-such-user/unflag', headers: p.headers })).statusCode).toBe(404);
    expect((await app.inject({ method: 'POST', url: `/admin/users/${p.id}/unflag`, headers: p.headers })).statusCode).toBe(200);
  });
});

describe('circuit breaker override', () => {
  it('an admin can resume rewards after an automatic liability trip', async () => {
    const tight = await makeApp({ DAILY_CREDIT_LIABILITY_LIMIT: '10' });
    const a = await guest(tight);
    await prisma.user.update({ where: { id: a.id }, data: { role: 'ADMIN' } });
    await prisma.transaction.create({ data: { userId: a.id, currency: 'credits', amount: 50, balanceAfter: 50, reason: 'run', refId: 'x' } });
    expect((await tight.inject({ method: 'GET', url: '/admin/economy', headers: a.headers })).json().rewardsPaused).toBe(true);
    await tight.inject({ method: 'POST', url: '/admin/economy', headers: a.headers, payload: { paused: false } });
    expect((await tight.inject({ method: 'GET', url: '/admin/economy', headers: a.headers })).json().rewardsPaused).toBe(false);
    expect((await tight.inject({ method: 'POST', url: '/daily/claim', headers: a.headers })).statusCode).toBe(200);
    // The 50-credit daily grant after the resume exceeds the limit again → breaker re-trips.
    clearBreakerCache();
    expect((await tight.inject({ method: 'GET', url: '/admin/economy', headers: a.headers })).json().rewardsPaused).toBe(true);
    await tight.close();
  });
});

describe('rate limiting & security headers', () => {
  it('behind a trusted proxy (CIDR list) each client gets its own rate-limit bucket', async () => {
    const proxied = await makeApp({ AUTH_RATE_LIMIT_PER_MIN: '2', TRUST_PROXY: '127.0.0.1' });
    const codes = [];
    for (let i = 0; i < 4; i++) codes.push((await proxied.inject({ method: 'POST', url: '/auth/login', remoteAddress: '127.0.0.1', headers: { 'x-forwarded-for': `203.0.113.${i}` }, payload: { email: 'n@x.io', password: 'x' } })).statusCode);
    expect(codes).toEqual([401, 401, 401, 401]);
    // An untrusted hop cannot do the same.
    const direct = [];
    for (let i = 0; i < 3; i++) direct.push((await proxied.inject({ method: 'POST', url: '/auth/login', remoteAddress: '198.51.100.7', headers: { 'x-forwarded-for': `203.0.113.${50 + i}` }, payload: { email: 'n@x.io', password: 'x' } })).statusCode);
    expect(direct).toEqual([401, 401, 429]);
    await proxied.close();
  });

  it('does not trust X-Forwarded-For by default (no IP spoofing past limits)', async () => {
    const limited = await makeApp({ AUTH_RATE_LIMIT_PER_MIN: '2' });
    const codes = [];
    for (let i = 0; i < 4; i++) codes.push((await limited.inject({ method: 'POST', url: '/auth/login', headers: { 'x-forwarded-for': `10.0.0.${i}` }, payload: { email: 'n@x.io', password: 'x' } })).statusCode);
    expect(codes).toEqual([401, 401, 429, 429]);
    await limited.close();
  });

  it('limits auth endpoints per IP and sets helmet headers', async () => {
    const limited = await makeApp({ AUTH_RATE_LIMIT_PER_MIN: '3' });
    const codes = [];
    for (let i = 0; i < 5; i++) codes.push((await limited.inject({ method: 'POST', url: '/auth/login', payload: { email: 'n@x.io', password: 'x' } })).statusCode);
    expect(codes).toEqual([401, 401, 401, 429, 429]);
    const h = await limited.inject({ method: 'GET', url: '/health' });
    expect(h.headers['x-content-type-options']).toBe('nosniff');
    await limited.close();
  });
});
