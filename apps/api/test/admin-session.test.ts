import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { makeApp, prisma, resetDb } from './helpers';

let app: FastifyInstance;
beforeAll(async () => { app = await makeApp(); });
afterAll(async () => { await app.close(); });
beforeEach(resetDb);

const ORIGIN = 'http://localhost:5173';

async function adminUser(email = 'boss@x.io', role: 'ADMIN' | 'PLAYER' = 'ADMIN') {
  await app.inject({ method: 'POST', url: '/auth/register', payload: { email, password: 'boss-pass-123' } });
  await prisma.user.update({ where: { email }, data: { role } });
}

function cookieFrom(res: { headers: Record<string, unknown> }): string {
  const raw = res.headers['set-cookie'];
  return String(Array.isArray(raw) ? raw[0] : raw);
}

describe('admin cookie session', () => {
  it('sets an httpOnly SameSite=Strict cookie and authorises admin reads', async () => {
    await adminUser();
    const login = await app.inject({ method: 'POST', url: '/auth/admin-session', headers: { origin: ORIGIN }, payload: { email: 'boss@x.io', password: 'boss-pass-123' } });
    expect(login.statusCode).toBe(200);
    const set = cookieFrom(login);
    expect(set).toMatch(/^golge_admin=/);
    expect(set).toContain('HttpOnly');
    expect(set).toContain('SameSite=Strict');
    expect(login.json()).toEqual({ ok: true }); // no token in the body
    const cookie = set.split(';')[0]!;
    expect((await app.inject({ method: 'GET', url: '/admin/economy', headers: { cookie } })).statusCode).toBe(200);
  });

  it('blocks cross-site (CSRF) writes and non-admins', async () => {
    await adminUser();
    const login = await app.inject({ method: 'POST', url: '/auth/admin-session', headers: { origin: ORIGIN }, payload: { email: 'boss@x.io', password: 'boss-pass-123' } });
    const cookie = cookieFrom(login).split(';')[0]!;
    const evil = await app.inject({ method: 'POST', url: '/admin/economy', headers: { cookie, origin: 'https://evil.example' }, payload: { paused: true } });
    expect(evil.statusCode).toBe(403);
    const noOrigin = await app.inject({ method: 'POST', url: '/admin/economy', headers: { cookie }, payload: { paused: true } });
    expect(noOrigin.statusCode).toBe(403);
    const ok = await app.inject({ method: 'POST', url: '/admin/economy', headers: { cookie, origin: ORIGIN }, payload: { paused: true } });
    expect(ok.statusCode).toBe(200);
    expect((await app.inject({ method: 'POST', url: '/auth/admin-session', headers: { origin: 'https://evil.example' }, payload: { email: 'boss@x.io', password: 'boss-pass-123' } })).statusCode).toBe(403);
    await adminUser('player@x.io', 'PLAYER');
    expect((await app.inject({ method: 'POST', url: '/auth/admin-session', headers: { origin: ORIGIN }, payload: { email: 'player@x.io', password: 'boss-pass-123' } })).statusCode).toBe(401);
  });

  it('rejects forged / player-scoped cookies and is revoked by logout-all', async () => {
    await adminUser();
    expect((await app.inject({ method: 'GET', url: '/admin/economy', headers: { cookie: 'golge_admin=not-a-jwt' } })).statusCode).toBe(401);
    const user = await prisma.user.findUniqueOrThrow({ where: { email: 'boss@x.io' } });
    const playerToken = app.jwt.sign({ sub: user.id, tv: 0 });
    expect((await app.inject({ method: 'GET', url: '/admin/economy', headers: { cookie: `golge_admin=${playerToken}` } })).statusCode).toBe(401);
    const login = await app.inject({ method: 'POST', url: '/auth/admin-session', headers: { origin: ORIGIN }, payload: { email: 'boss@x.io', password: 'boss-pass-123' } });
    const cookie = cookieFrom(login).split(';')[0]!;
    await prisma.user.update({ where: { id: user.id }, data: { tokenVersion: { increment: 1 } } });
    expect((await app.inject({ method: 'GET', url: '/admin/economy', headers: { cookie } })).statusCode).toBe(401);
    const out = await app.inject({ method: 'POST', url: '/auth/admin-session/logout', headers: { origin: ORIGIN } });
    expect(cookieFrom(out)).toMatch(/golge_admin=;/);
  });
});
