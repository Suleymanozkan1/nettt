import { CATALOG } from '@stage/shared';
import { h } from '../ui/dom';
import './admin.css';

const BASE = (import.meta.env.VITE_API_URL as string | undefined) ?? 'http://localhost:3000';
const root = document.getElementById('admin')!;

/** The admin session is an httpOnly SameSite=Strict cookie set by the API; no token is visible to JS. */
async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    credentials: 'include',
    headers: body !== undefined ? { 'content-type': 'application/json' } : {},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.message ?? data.error ?? `HTTP ${res.status}`);
  return data as T;
}

const fmt = (n: number) => n.toLocaleString('tr-TR');
const status = (msg: string, ok = true) => h('p', { class: ok ? 'ok' : 'err', role: 'status' }, msg);

/**
 * Click handler for a dashboard action: the button is disabled while the request runs (no duplicate submits), and a
 * failure is shown in `msg` while the current view stays in place for a retry.
 */
function action(msg: HTMLElement, fn: () => Promise<unknown>): (ev: Event) => Promise<void> {
  return async (ev) => {
    const button = ev.currentTarget as HTMLButtonElement | null;
    if (button?.disabled) return;
    if (button) button.disabled = true;
    try { await fn(); } catch (e) { msg.replaceChildren(status((e as Error).message || 'İstek başarısız', false)); }
    finally { if (button) button.disabled = false; }
  };
}

function table(headers: string[], rows: (string | number | Node)[][]): HTMLElement {
  return h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, ...headers.map((x) => h('th', {}, x)))),
    h('tbody', {}, ...rows.map((r) => h('tr', {}, ...r.map((c) => h('td', {}, typeof c === 'number' ? fmt(c) : c)))))));
}

function login(): void {
  const email = h('input', { type: 'email', placeholder: 'admin e-posta', autocomplete: 'username' });
  const pass = h('input', { type: 'password', placeholder: 'şifre', autocomplete: 'current-password' });
  const msg = h('div');
  root.replaceChildren(h('h1', {}, 'Gölge Kuklacı · Yönetim'), h('section', {}, h('h2', {}, 'Giriş'),
    h('div', { class: 'row' }, email, pass, h('button', { class: 'primary', onclick: async () => {
      try {
        await call('POST', '/auth/admin-session', { email: email.value, password: pass.value });
        void dashboard();
      } catch (e) { msg.replaceChildren(status(`Giriş başarısız: ${(e as Error).message}`, false)); }
    } }, 'Giriş')), msg,
    h('p', { class: 'muted' }, 'Yalnızca ADMIN rolündeki hesaplar. Rol her istekte sunucuda veritabanından kontrol edilir.')));
}

interface Economy { rewardsPaused: boolean; liabilityLimit24h: number; granted24h: Record<string, number>; outstanding: { credits: number; gems: number }; runs24h: number; rejected24h: number; dau: number; duels24h: number; events24h: Record<string, number> }

async function economySection(): Promise<HTMLElement> {
  const e = await call<Economy>('GET', '/admin/economy');
  const stat = (label: string, value: string | number) => h('div', { class: 'stat' }, h('small', {}, label), h('b', {}, typeof value === 'number' ? fmt(value) : value));
  const msg = h('div');
  const box: HTMLElement = h('section', { 'data-testid': 'economy' }, h('h2', {}, 'Ekonomi & analitik (son 24 saat)'),
    h('div', { class: 'grid' },
      stat('Ödüller', e.rewardsPaused ? 'DURDURULDU' : 'Açık'), stat('Günlük aktif oyuncu', e.dau), stat('Doğrulanan gösteri', e.runs24h),
      stat('Reddedilen gösteri', e.rejected24h), stat('Düello', e.duels24h), stat('Verilen jeton', e.granted24h.credits ?? 0),
      stat('Verilen elmas', e.granted24h.gems ?? 0), stat('Oyuncu bakiyesi (jeton)', e.outstanding.credits), stat('Oyuncu bakiyesi (elmas)', e.outstanding.gems),
      stat('Yükümlülük sınırı', e.liabilityLimit24h)),
    h('div', { class: 'row' }, h('button', { class: e.rewardsPaused ? 'primary' : 'danger', 'data-testid': 'toggle-rewards', onclick: action(msg, async () => {
      await call('POST', '/admin/economy', { paused: !e.rewardsPaused });
      box.replaceWith(await economySection());
    }) }, e.rewardsPaused ? 'Ödülleri yeniden aç' : 'Ödülleri durdur (devre kesici)')), msg,
    table(['Olay türü', 'Adet'], Object.entries(e.events24h).sort((a, b) => b[1] - a[1]).map(([k, v]) => [k, v])));
  return box;
}

interface UserRow { id: string; displayName: string; email: string | null; credits: number; gems: number; bestScore: number; flagged: boolean; role: string }

async function usersSection(): Promise<HTMLElement> {
  const q = h('input', { type: 'search', placeholder: 'id, ad veya e-posta (boş = işaretliler)', 'aria-label': 'Kullanıcı ara' });
  const out = h('div');
  const msg = h('div');
  const search = async () => {
    msg.replaceChildren();
    const { users } = await call<{ users: UserRow[] }>('GET', `/admin/users?q=${encodeURIComponent(q.value)}`);
    out.replaceChildren(table(['Ad', 'E-posta', 'Jeton', 'Elmas', 'En iyi', 'Durum', ''], users.map((u) => [
      u.displayName, u.email ?? '—', u.credits, u.gems, u.bestScore, u.flagged ? '⚠ işaretli' : u.role,
      h('button', { onclick: action(msg, () => detail(u.id)) }, 'Aç'),
    ])));
  };
  const detail = async (id: string) => {
    msg.replaceChildren();
    const [{ user, runs }, { transactions }] = await Promise.all([
      call<{ user: UserRow; runs: { startedAt: string; status: string; score: number; mode: string; rejectReason: string | null }[] }>('GET', `/admin/users/${id}`),
      call<{ transactions: { createdAt: string; currency: string; amount: number; reason: string; refId: string }[] }>('GET', `/admin/transactions?userId=${id}`),
    ]);
    const item = h('select', {}, ...CATALOG.filter((c) => c.price > 0).map((c) => h('option', { value: c.id }, `${c.name} (${c.id})`)));
    const itemMsg = h('div');
    out.replaceChildren(h('h2', {}, `${user.displayName} (${user.id})`),
      h('div', { class: 'row' },
        user.flagged ? h('button', { onclick: action(itemMsg, async () => { await call('POST', `/admin/users/${id}/unflag`); await detail(id); }) }, 'İşareti kaldır') : null,
        item, h('button', { class: 'danger', onclick: action(itemMsg, async () => {
          const r = await call<{ refunded: number; currency: string }>('POST', `/admin/users/${id}/refund`, { itemId: item.value });
          itemMsg.replaceChildren(status(`İade edildi: ${r.refunded} ${r.currency}`));
        }) }, 'Ürünü iade et')), itemMsg,
      h('h2', {}, 'Son gösteriler'), table(['Zaman', 'Mod', 'Durum', 'Skor', 'Ret nedeni'], runs.map((r) => [new Date(r.startedAt).toLocaleString('tr-TR'), r.mode, r.status, r.score, r.rejectReason ?? '—'])),
      h('h2', {}, 'Hesap hareketleri'), table(['Zaman', 'Para', 'Miktar', 'Neden', 'Ref'], transactions.map((t) => [new Date(t.createdAt).toLocaleString('tr-TR'), t.currency, t.amount, t.reason, t.refId])));
  };
  search().catch((e: Error) => msg.replaceChildren(status(e.message || 'İstek başarısız', false)));
  return h('section', {}, h('h2', {}, 'Oyuncular'), h('div', { class: 'row' }, q, h('button', { onclick: action(msg, search) }, 'Ara')), msg, out);
}

interface GameEvent { id: string; name: string; startsAt: string; endsAt: string; fansMult: number; creditsMult: number }

async function eventsSection(): Promise<HTMLElement> {
  const { events } = await call<{ events: GameEvent[] }>('GET', '/admin/events');
  const name = h('input', { placeholder: 'Etkinlik adı' });
  const starts = h('input', { type: 'datetime-local', 'aria-label': 'Başlangıç' });
  const ends = h('input', { type: 'datetime-local', 'aria-label': 'Bitiş' });
  const fans = h('input', { type: 'number', min: 1, max: 3, step: 0.1, value: 1.5, 'aria-label': 'Hayran çarpanı' });
  const credits = h('input', { type: 'number', min: 1, max: 3, step: 0.1, value: 1, 'aria-label': 'Jeton çarpanı' });
  const msg = h('div');
  const box: HTMLElement = h('section', {}, h('h2', {}, 'Özel etkinlikler'),
    table(['Ad', 'Başlangıç', 'Bitiş', 'Hayran', 'Jeton', ''], events.map((e) => [e.name, new Date(e.startsAt).toLocaleString('tr-TR'), new Date(e.endsAt).toLocaleString('tr-TR'), `x${e.fansMult}`, `x${e.creditsMult}`,
      h('button', { class: 'danger', onclick: action(msg, async () => { await call('DELETE', `/admin/events/${e.id}`); box.replaceWith(await eventsSection()); }) }, 'Sil')])),
    h('div', { class: 'row' }, name, starts, ends, fans, credits, h('button', { class: 'primary', onclick: action(msg, async () => {
      await call('POST', '/admin/events', { name: name.value, startsAt: new Date(starts.value).toISOString(), endsAt: new Date(ends.value).toISOString(), fansMult: Number(fans.value), creditsMult: Number(credits.value) });
      box.replaceWith(await eventsSection());
    }) }, 'Ekle')), msg);
  return box;
}

async function dashboard(): Promise<void> {
  try {
    const sections = await Promise.all([economySection(), usersSection(), eventsSection()]);
    root.replaceChildren(h('div', { class: 'row' }, h('h1', {}, 'Gölge Kuklacı · Yönetim'),
      h('button', { onclick: async () => { await call('POST', '/auth/admin-session/logout').catch(() => undefined); login(); } }, 'Çıkış')), ...sections);
  } catch {
    login();
  }
}

void dashboard();
