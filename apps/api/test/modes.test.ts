import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { CHALLENGE_ATTEMPTS_PER_DAY, computeRunRewards, isoWeekKey, challengeSeed } from '@stage/shared';
import { backdateRun, guest, makeApp, playInputs, prisma, resetDb } from './helpers';

let app: FastifyInstance;
beforeAll(async () => { app = await makeApp(); });
afterAll(async () => { await app.close(); });
beforeEach(async () => { await resetDb(); await prisma.$executeRawUnsafe('TRUNCATE "GameEvent","DuelMatch" CASCADE'); });

async function play(headers: Record<string, string>, body: object = {}, perfect = 4) {
  const start = await app.inject({ method: 'POST', url: '/runs', headers, payload: body });
  if (start.statusCode !== 200) return { start, fin: null };
  const { runId, seed, params } = start.json();
  const { inputs, durationMs } = playInputs(seed, params, perfect);
  await backdateRun(runId, durationMs + 1000);
  const fin = await app.inject({ method: 'POST', url: `/runs/${runId}/finish`, headers, payload: { inputs } });
  return { start, fin };
}

describe('token revocation', () => {
  it('logout-all invalidates existing tokens', async () => {
    const g = await guest(app);
    expect((await app.inject({ method: 'POST', url: '/auth/logout-all', headers: g.headers })).statusCode).toBe(200);
    expect((await app.inject({ method: 'GET', url: '/me', headers: g.headers })).statusCode).toBe(401);
  });
});

describe('onboarding', () => {
  it('sets a display name and validates it', async () => {
    const g = await guest(app);
    expect((await app.inject({ method: 'GET', url: '/me', headers: g.headers })).json().onboarded).toBe(false);
    expect((await app.inject({ method: 'POST', url: '/me/onboarding', headers: g.headers, payload: { displayName: '<b>x</b>' } })).statusCode).toBe(400);
    await app.inject({ method: 'POST', url: '/me/onboarding', headers: g.headers, payload: { displayName: 'Gölge Usta' } });
    expect((await app.inject({ method: 'GET', url: '/me', headers: g.headers })).json()).toMatchObject({ onboarded: true, displayName: 'Gölge Usta' });
  });
});

describe('level select', () => {
  it('locks later acts until reached, then starts there', async () => {
    const g = await guest(app);
    expect((await app.inject({ method: 'GET', url: '/me', headers: g.headers })).json().startActs).toEqual([1]);
    expect((await app.inject({ method: 'POST', url: '/runs', headers: g.headers, payload: { startAct: 6 } })).statusCode).toBe(403);
    await prisma.user.update({ where: { id: g.id }, data: { maxAct: 7 } });
    const { start, fin } = await play(g.headers, { startAct: 6 });
    expect(start.json().params.startAct).toBe(6);
    expect(fin!.json().summary.level).toBeGreaterThanOrEqual(6);
  });
  it('records the furthest act reached', async () => {
    const g = await guest(app);
    await play(g.headers, {}, 12);
    expect((await prisma.user.findUniqueOrThrow({ where: { id: g.id } })).maxAct).toBe(2);
  });
});

describe('weekly challenge', () => {
  it('same seed for everyone, default params, own board, attempts capped, best score untouched', async () => {
    const a = await guest(app); const b = await guest(app);
    await prisma.upgradeLevel.create({ data: { userId: a.id, upgradeId: 'tolerance', level: 3 } });
    const ra = await play(a.headers, { mode: 'challenge' });
    const rb = await play(b.headers, { mode: 'challenge' }, 2);
    expect(ra.start.json()).toMatchObject({ mode: 'challenge', seed: challengeSeed(isoWeekKey()), params: { toleranceLevel: 0 } });
    expect(rb.start.json().seed).toBe(ra.start.json().seed);
    expect(ra.fin!.json().mode).toBe('challenge');
    expect((await prisma.user.findUniqueOrThrow({ where: { id: a.id } })).bestScore).toBe(0);
    const board = (await app.inject({ method: 'GET', url: '/leaderboard?period=challenge', headers: b.headers })).json();
    expect(board.entries.map((e: { score: number }) => e.score)).toEqual([ra.fin!.json().summary.score, rb.fin!.json().summary.score]);
    expect((await app.inject({ method: 'GET', url: '/leaderboard?period=weekly', headers: b.headers })).json().entries).toHaveLength(0);
    for (let i = 1; i < CHALLENGE_ATTEMPTS_PER_DAY; i++) await app.inject({ method: 'POST', url: '/runs', headers: a.headers, payload: { mode: 'challenge' } });
    expect((await app.inject({ method: 'POST', url: '/runs', headers: a.headers, payload: { mode: 'challenge' } })).statusCode).toBe(429);
  });
});

describe('weekly challenge concurrency', () => {
  it('parallel starts cannot exceed the daily attempt cap', async () => {
    const g = await guest(app);
    const codes = await Promise.all(Array.from({ length: CHALLENGE_ATTEMPTS_PER_DAY + 3 }, () =>
      app.inject({ method: 'POST', url: '/runs', headers: g.headers, payload: { mode: 'challenge' } }).then((r) => r.statusCode)));
    expect(codes.filter((c) => c === 200)).toHaveLength(CHALLENGE_ATTEMPTS_PER_DAY);
    expect(codes.filter((c) => c === 429)).toHaveLength(3);
  });
});

describe('special events', () => {
  it('active event multiplies run rewards and is visible', async () => {
    const g = await guest(app);
    await prisma.gameEvent.create({ data: { name: 'Gece Gösterisi', startsAt: new Date(Date.now() - 1000), endsAt: new Date(Date.now() + 3_600_000), fansMult: 2, creditsMult: 1.5 } });
    expect((await app.inject({ method: 'GET', url: '/events/active', headers: g.headers })).json().event).toMatchObject({ name: 'Gece Gösterisi', fansMult: 2 });
    const { fin } = await play(g.headers);
    const body = fin!.json();
    expect(body.event).toEqual({ name: 'Gece Gösterisi' });
    expect(body.rewards).toEqual(computeRunRewards(body.summary, { fans: 2, credits: 1.5 }));
  });
});

describe('admin: refunds, events, users, metrics', () => {
  it('refunds a purchase once and resets the loadout', async () => {
    const adm = await guest(app); const p = await guest(app);
    await prisma.user.update({ where: { id: adm.id }, data: { role: 'ADMIN' } });
    await prisma.user.update({ where: { id: p.id }, data: { credits: 300 } });
    await app.inject({ method: 'POST', url: '/shop/buy', headers: p.headers, payload: { itemId: 'lamp_neon' } });
    await app.inject({ method: 'POST', url: '/loadout', headers: p.headers, payload: { skin: 'lamp_neon' } });
    expect((await app.inject({ method: 'POST', url: `/admin/users/${p.id}/refund`, headers: p.headers, payload: { itemId: 'lamp_neon' } })).statusCode).toBe(403);
    const r = await app.inject({ method: 'POST', url: `/admin/users/${p.id}/refund`, headers: adm.headers, payload: { itemId: 'lamp_neon' } });
    expect(r.json()).toMatchObject({ refunded: 300, currency: 'credits' });
    expect((await app.inject({ method: 'POST', url: `/admin/users/${p.id}/refund`, headers: adm.headers, payload: { itemId: 'lamp_neon' } })).statusCode).toBe(409);
    expect(await prisma.user.findUniqueOrThrow({ where: { id: p.id } })).toMatchObject({ credits: 300, skin: 'lamp_candle' });
  });

  it('manages events, searches users, exposes metrics', async () => {
    const adm = await guest(app);
    await prisma.user.update({ where: { id: adm.id }, data: { role: 'ADMIN' } });
    const bad = await app.inject({ method: 'POST', url: '/admin/events', headers: adm.headers, payload: { name: 'X1', startsAt: '2026-10-02T00:00:00Z', endsAt: '2026-10-01T00:00:00Z' } });
    expect(bad.statusCode).toBe(400);
    const ev = await app.inject({ method: 'POST', url: '/admin/events', headers: adm.headers, payload: { name: 'Hafta sonu', startsAt: '2026-10-02T00:00:00Z', endsAt: '2026-10-04T00:00:00Z', fansMult: 1.5 } });
    expect(ev.statusCode).toBe(200);
    expect((await app.inject({ method: 'GET', url: '/admin/events', headers: adm.headers })).json().events).toHaveLength(1);
    await app.inject({ method: 'DELETE', url: `/admin/events/${ev.json().id}`, headers: adm.headers });
    expect((await app.inject({ method: 'GET', url: '/admin/events', headers: adm.headers })).json().events).toHaveLength(0);
    const users = (await app.inject({ method: 'GET', url: `/admin/users?q=${adm.id}`, headers: adm.headers })).json().users;
    expect(users[0].id).toBe(adm.id);
    const m = await app.inject({ method: 'GET', url: '/metrics' });
    expect(m.body).toContain('golge_http_requests_total');
  });
});
