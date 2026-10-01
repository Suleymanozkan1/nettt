import Phaser from 'phaser';
import { CATALOG, DEFAULT_PARAMS, MAX_REVIVES, ROUNDS_PER_LEVEL, BOSS_EVERY, startLives, type RunInput, type RunSummary, type SimEvent } from '@stage/shared';
import { api, ApiError, type FinishResult, type Profile } from '../lib/api';
import { setSoundEnabled, sfx } from '../lib/audio';
import { setHapticsEnabled } from '../lib/haptics';
import type { StageScene } from '../game/StageScene';
import { h, toast, fmt } from './dom';

type Screen = 'home' | 'hud' | 'pause' | 'gameover' | 'results' | 'shop' | 'leaderboard' | 'daily' | 'missions' | 'profile' | 'settings' | 'notifications' | 'levels';

interface RunCtx { runId: string | null; offline: boolean; score: number; combo: number; level: number; lives: number }

const ERRORS: Record<string, string> = {
  insufficient_funds: 'Yetersiz bakiye', already_owned: 'Zaten sende', already_claimed: 'Bugün zaten alındı',
  rewards_paused: 'Ödüller şu an geçici olarak durduruldu', email_taken: 'Bu e-posta kayıtlı', invalid_credentials: 'E-posta veya şifre hatalı',
  validation_error: 'Bilgileri kontrol et', too_many_accounts: 'Bu ağdan çok fazla hesap açıldı', offline: 'Bağlantı yok',
};
const errText = (e: unknown) => (e instanceof ApiError ? ERRORS[e.code] ?? 'Bir şeyler ters gitti' : 'Bir şeyler ters gitti');

export class App {
  profile: Profile | null = null;
  offline = false;
  private bootError: string | null = null;
  private run: RunCtx | null = null;
  private notes: { text: string; go: Screen }[] = [];

  constructor(private readonly root: HTMLElement, private readonly game: Phaser.Game) {}

  private get scene(): StageScene { return this.game.scene.getScene('stage') as StageScene; }

  async boot(): Promise<void> {
    try {
      await api.init();
      await this.refresh();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        await api.logout();
        return this.boot();
      }
      this.offline = true;
      if (e instanceof ApiError && e.code !== 'offline') this.bootError = errText(e);
    }
    this.show('home');
  }

  async refresh(): Promise<void> {
    this.profile = await api.me();
    setSoundEnabled(this.profile.settings.sound);
    setHapticsEnabled(this.profile.settings.haptics);
    this.offline = false;
    await this.refreshNotes();
  }

  /** In-app notifications derived from live server state (no push, nothing sent outside the app). */
  private async refreshNotes(): Promise<void> {
    this.notes = [];
    if (this.profile && !this.profile.settings.notifications) return;
    try {
      const [daily, missions] = await Promise.all([api.daily(), api.missions()]);
      if (daily.canClaim) this.notes.push({ text: `Günlük ödül hazır (${daily.nextCycleDay}. gün)`, go: 'daily' });
      for (const m of missions.missions) if (m.completed && !m.claimed) this.notes.push({ text: `Görev tamam: ${m.label}`, go: 'missions' });
    } catch { /* offline */ }
  }

  show(screen: Screen): void {
    this.root.replaceChildren();
    this.root.dataset.screen = screen;
    const view = this.render(screen);
    if (view) this.root.append(view);
  }

  private render(screen: Screen): HTMLElement | null {
    switch (screen) {
      case 'home': return this.home();
      case 'hud': return this.hud();
      case 'pause': return this.pause();
      case 'gameover': return this.gameOver();
      case 'shop': return this.lazy(() => this.shop());
      case 'leaderboard': return this.lazy(() => this.leaderboard('all'));
      case 'daily': return this.lazy(() => this.daily());
      case 'missions': return this.lazy(() => this.missions());
      case 'profile': return this.lazy(() => this.profileView());
      case 'settings': return this.settings();
      case 'notifications': return this.notifications();
      case 'levels': return this.levels();
      case 'results': return null;
    }
  }

  private lazy(load: () => Promise<HTMLElement>): HTMLElement {
    const box = h('div', { class: 'panel' }, h('p', { class: 'muted' }, 'Yükleniyor…'));
    load().then((v) => { if (box.isConnected) box.replaceWith(v); }).catch((e: unknown) => {
      box.replaceChildren(this.back(), h('p', { class: 'error' }, e instanceof ApiError && e.code === 'offline' ? 'Çevrimdışısın. Bunu görmek için bağlan.' : 'Bir şeyler ters gitti.'));
    });
    return box;
  }

  private back(to: Screen = 'home'): HTMLElement {
    return h('button', { class: 'back', 'aria-label': 'Geri', onclick: () => { sfx.click(); this.show(to); } }, '‹ Geri');
  }

  private bar(): HTMLElement {
    const p = this.profile;
    return h('div', { class: 'topbar' },
      h('span', { class: 'chip', title: 'Jeton' }, '🪙 ', h('b', { 'data-testid': 'credits' }, p ? fmt(p.credits) : '—')),
      h('span', { class: 'chip', title: 'Elmas' }, '💎 ', h('b', { 'data-testid': 'gems' }, p ? fmt(p.gems) : '—')),
      h('span', { class: 'chip', title: 'Hayran' }, '👏 ', h('b', {}, p ? fmt(p.fans) : '—')),
    );
  }

  // ---------------- Home ----------------
  private home(): HTMLElement {
    const p = this.profile;
    const nav = (label: string, screen: Screen, badge = false) =>
      h('button', { class: `tile${badge ? ' badge' : ''}`, onclick: () => { sfx.click(); this.show(screen); } }, label);
    const progress = p ? (p.fans - p.levelFloor) / Math.max(1, p.nextLevelFans - p.levelFloor) : 0;
    return h('div', { class: 'panel home' },
      this.bar(),
      this.offline ? h('p', { class: 'banner' }, `${this.bootError ? `${this.bootError}. ` : 'Çevrimdışı. '}Antrenman modu: puan ve ödüller kaydedilmez.`) : null,
      h('h1', { class: 'logo' }, 'GÖLGE', h('br'), 'KUKLACI'),
      h('p', { class: 'muted tagline' }, 'Lambayı doğru anda durdur, gölgeyi kalıba oturt.'),
      p ? h('div', { class: 'level' },
        h('span', {}, `Sv ${p.level} · ${p.displayName}`),
        h('div', { class: 'progress', role: 'progressbar', 'aria-valuenow': Math.round(progress * 100) }, h('i', { style: `width:${Math.round(progress * 100)}%` })),
        h('small', { class: 'muted' }, `${fmt(p.fans)} / ${fmt(p.nextLevelFans)} hayran · En iyi ${fmt(p.bestScore)}`)) : null,
      h('button', { class: 'play', 'data-testid': 'play', onclick: () => void this.startRun() }, '▶ GÖSTERİ'),
      h('div', { class: 'grid' },
        nav('🎁 Günlük', 'daily', this.notes.some((n) => n.go === 'daily')),
        nav('🎯 Görevler', 'missions', this.notes.some((n) => n.go === 'missions')),
        nav('🏆 Sıralama', 'leaderboard'),
        nav('🛍 Mağaza', 'shop'),
        nav('🎭 Profil', 'profile'),
        nav('🎪 Perdeler', 'levels'),
        nav(`🔔 Gelen${this.notes.length ? ` (${this.notes.length})` : ''}`, 'notifications', this.notes.length > 0),
        nav('⚙ Ayarlar', 'settings'),
      ),
    );
  }

  // ---------------- Run lifecycle ----------------
  async startRun(): Promise<void> {
    sfx.click();
    let runId: string | null = null;
    let seed = Math.floor(Math.random() * 2 ** 31);
    let params = DEFAULT_PARAMS;
    let offline = this.offline;
    if (!offline) {
      try {
        ({ runId, seed, params } = await api.startRun());
      } catch (e) {
        if (e instanceof ApiError && e.code === 'offline') { offline = true; toast('Çevrimdışı: antrenman gösterisi, kaydedilmez', 'error'); }
        else { toast('Gösteri başlatılamadı, tekrar dene.', 'error'); return; }
      }
    }
    const lamp = CATALOG.find((c) => c.id === this.profile?.skin) ?? CATALOG[0]!;
    const puppet = CATALOG.find((c) => c.id === this.profile?.character);
    this.run = { runId, offline, score: 0, combo: 0, level: 1, lives: startLives(params) };
    this.scene.startRun(seed, params, lamp.colors[0]!, puppet?.colors[0] ?? 0xff8c42, {
      onRound: (ev) => this.onRound(ev),
      onBoss: (level) => toast(`BOSS PERDESİ ${level}: Rüzgâr perdeyi dalgalandırıyor!`, 'error'),
      onDead: () => this.show('gameover'),
    });
    this.show('hud');
    if (this.profile && !this.profile.tutorialDone) this.showTutorial();
  }

  private showTutorial(): void {
    this.root.append(h('div', { class: 'tutorial', 'data-testid': 'tutorial' },
      h('div', { class: 'hand' }, '👆'),
      h('p', {}, 'Lamba sallanıyor, kuklanın gölgesi duvarda kayıp büyüyor.'),
      h('p', {}, 'Gölge parlayan kalıba oturduğu an ekrana dokun (veya Boşluk).'),
      h('p', {}, 'Tam oturtursan combo! Iskalarsan ya da süre biterse bir spot ışığı söner.')));
  }

  private onRound(ev: Extract<SimEvent, { type: 'fit' | 'timeout' }>): void {
    const run = this.run;
    if (!run) return;
    if (ev.type === 'fit') Object.assign(run, { score: ev.score, combo: ev.combo });
    else run.combo = 0;
    const levelUp = ev.level > run.level;
    Object.assign(run, { level: ev.level, lives: ev.lives });
    document.querySelector('[data-testid="tutorial"]')?.remove();
    if (this.profile && !this.profile.tutorialDone) {
      this.profile.tutorialDone = true;
      if (!run.offline) void api.updateSettings({ tutorialDone: true }).catch(() => undefined);
    }
    this.updateHud(ev.type === 'fit' && ev.grade === 'perfect');
    if (levelUp && ev.level % BOSS_EVERY !== 0) toast(`${ev.level}. perde! Lamba hızlanıyor`, 'info');
  }

  private updateHud(hot = false): void {
    const run = this.run;
    if (!run) return;
    const set = (id: string, text: string) => { const el = this.root.querySelector(`[data-testid="${id}"]`); if (el) el.textContent = text; };
    set('score', String(run.score));
    set('combo', run.combo > 1 ? `Combo x${run.combo}` : '');
    set('level', `Perde ${run.level}`);
    set('lives', '💡'.repeat(Math.max(0, run.lives)) || '—');
    this.root.querySelector('[data-testid="combo"]')?.classList.toggle('hot', hot);
  }

  private hud(): HTMLElement {
    const el = h('div', { class: 'hud' },
      h('div', { class: 'hud-top' },
        h('span', { class: 'hud-level', 'data-testid': 'level' }),
        h('span', { class: 'hud-lives', 'data-testid': 'lives', 'aria-label': 'Kalan spot ışığı' }),
        h('button', { class: 'pause-btn', 'aria-label': 'Duraklat', 'data-testid': 'pause', onclick: (e) => { e.stopPropagation(); this.scene.paused = true; this.show('pause'); } }, 'II')),
      h('div', { class: 'hud-score', 'data-testid': 'score' }),
      h('div', { class: 'hud-combo', 'data-testid': 'combo' }),
    );
    queueMicrotask(() => this.updateHud());
    return el;
  }

  private pause(): HTMLElement {
    return h('div', { class: 'panel modal' },
      h('h2', {}, 'Ara'),
      h('button', { class: 'primary', onclick: () => { this.scene.paused = false; this.show('hud'); } }, 'Devam'),
      h('button', { onclick: () => { this.scene.paused = false; this.scene.quit(); void this.finish(); } }, 'Gösteriyi bitir ve ödülleri al'),
    );
  }

  private gameOver(): HTMLElement {
    const sim = this.scene.sim;
    const cost = this.profile?.reviveCost ?? 5;
    const canRevive = !!sim && !this.run?.offline && sim.revives < MAX_REVIVES && (this.profile?.gems ?? 0) >= cost;
    return h('div', { class: 'panel modal' },
      h('h2', {}, 'Işıklar söndü!'),
      h('p', { class: 'big' }, String(sim?.score ?? 0)),
      canRevive ? h('button', { class: 'primary', 'data-testid': 'revive', onclick: () => {
        if (this.scene.revive()) { this.profile!.gems -= cost; if (this.run) this.run.lives = 1; this.show('hud'); }
      } }, `Bis! 💎${cost} ile devam`) : h('p', { class: 'muted' }, sim && sim.revives >= MAX_REVIVES ? 'Bu gösteride bis hakkını kullandın.' : `Bis ${cost} elmas. Elmaslar boss perdelerinden ve günlük ödülden gelir.`),
      h('button', { 'data-testid': 'results', onclick: () => void this.finish() }, 'Sonuçlar'),
    );
  }

  private async finish(): Promise<void> {
    const run = this.run;
    const sim = this.scene.sim;
    if (!run || !sim) return;
    const inputs: RunInput[] = [...this.scene.inputs];
    this.root.replaceChildren(h('div', { class: 'panel modal' }, h('p', {}, 'Gösteri doğrulanıyor…')));
    let result: FinishResult | null = null;
    let error: string | null = null;
    if (run.runId && !run.offline) {
      try {
        result = await api.finishRun(run.runId, inputs);
        await this.refresh();
      } catch (e) {
        error = e instanceof ApiError && e.code === 'offline' ? 'Bağlantı koptu — bu gösteri kaydedilemedi.' : `Gösteri sayılmadı (${e instanceof ApiError ? e.message : 'hata'}).`;
      }
    }
    this.run = null;
    this.renderResults(sim.summary(), result, error, run.offline);
  }

  private renderResults(local: RunSummary, result: FinishResult | null, error: string | null, offline: boolean): void {
    this.root.dataset.screen = 'results';
    const s = result?.summary ?? local;
    if (result && (result.rewards.credits || result.rewards.gems)) sfx.reward();
    this.root.replaceChildren(h('div', { class: 'panel modal results', 'data-testid': 'results-panel' },
      h('h2', {}, result?.newBest ? '🎉 Yeni rekor!' : 'Perde kapandı'),
      h('p', { class: 'big', 'data-testid': 'final-score' }, String(s.score)),
      h('dl', { class: 'stats' },
        h('dt', {}, 'Oturan gölge'), h('dd', {}, String(s.fits)),
        h('dt', {}, 'Mükemmel'), h('dd', {}, String(s.perfects)),
        h('dt', {}, 'Kaçan'), h('dd', {}, String(s.misses)),
        h('dt', {}, 'En iyi combo'), h('dd', {}, `x${s.maxCombo}`),
        h('dt', {}, 'Perde'), h('dd', {}, String(s.level)),
      ),
      result ? h('div', { class: 'rewards', 'data-testid': 'rewards' },
        h('span', {}, `👏 +${result.rewards.fans} hayran`),
        h('span', {}, `🪙 +${result.rewards.credits}`),
        result.rewards.gems ? h('span', {}, `💎 +${result.rewards.gems}`) : null,
        h('small', { class: 'muted', 'data-testid': 'verified' }, '✔ Sunucu tarafından doğrulandı'),
        result.rewardsPaused ? h('small', { class: 'error' }, 'Ödüller oyun ekibi tarafından geçici olarak durduruldu.') : null) : null,
      result ? h('ul', { class: 'mission-mini' }, ...result.missions.map((m) => h('li', { class: m.completed ? 'done' : '' }, `${m.label}: ${m.progress}/${m.target}`))) : null,
      error ? h('p', { class: 'error' }, error) : null,
      offline ? h('p', { class: 'muted' }, 'Antrenman gösterisi — kaydedilmedi.') : null,
      h('button', { class: 'primary', 'data-testid': 'again', onclick: () => void this.startRun() }, 'Tekrar oyna'),
      h('button', { onclick: () => this.show('home') }, 'Ana sayfa'),
    ));
  }

  // ---------------- Meta screens ----------------
  private async shop(tab: 'skin' | 'character' | 'upgrades' = 'skin'): Promise<HTMLElement> {
    const data = await api.shop();
    const p = this.profile!;
    const tabs = h('div', { class: 'tabs' }, ...(['skin', 'character', 'upgrades'] as const).map((t) =>
      h('button', { class: t === tab ? 'on' : '', onclick: async () => panel.replaceWith(await this.shop(t)) }, t === 'skin' ? 'Lambalar' : t === 'character' ? 'Kuklalar' : 'Geliştirmeler')));
    const list = h('div', { class: 'list' });
    const act = async (fn: () => Promise<unknown>, ok: string) => {
      try { await fn(); sfx.reward(); toast(ok, 'reward'); await this.refresh(); panel.replaceWith(await this.shop(tab)); }
      catch (e) { toast(errText(e), 'error'); }
    };
    if (tab === 'upgrades') {
      for (const u of data.upgrades) {
        list.append(h('div', { class: 'row' },
          h('div', {}, h('b', {}, u.name), h('small', { class: 'muted' }, ` ${u.description} · Sv ${u.level}/${u.maxLevel}`)),
          u.nextCost === null ? h('span', { class: 'muted' }, 'MAKS')
            : h('button', { 'data-testid': `upgrade-${u.id}`, disabled: p.credits < u.nextCost, onclick: () => act(() => api.upgrade(u.id), `${u.name} geliştirildi`) }, `🪙 ${fmt(u.nextCost)}`)));
      }
    } else {
      for (const item of data.items.filter((i) => i.kind === tab)) {
        const equipped = item.id === p.skin || item.id === p.character;
        const swatch = h('span', { class: 'swatch' }, ...item.colors.map((c) => h('i', { style: `background:#${c.toString(16).padStart(6, '0')}` })));
        const price = `${item.currency === 'gems' ? '💎' : '🪙'} ${fmt(item.price)}`;
        const affordable = (item.currency === 'gems' ? p.gems : p.credits) >= item.price;
        list.append(h('div', { class: 'row' }, swatch, h('b', {}, item.name),
          equipped ? h('span', { class: 'muted' }, 'Kullanımda')
            : item.owned ? h('button', { onclick: () => act(() => api.loadout(item.kind === 'skin' ? { skin: item.id } : { character: item.id }), `${item.name} seçildi`) }, 'Kullan')
            : h('button', { 'data-testid': `buy-${item.id}`, disabled: !affordable, onclick: () => act(() => api.buy(item.id), `${item.name} açıldı`) }, price)));
      }
    }
    const panel = h('div', { class: 'panel' }, this.back(), this.bar(), h('h2', {}, 'Mağaza'),
      h('p', { class: 'muted small' }, 'Her şey oynayarak kazanılan para birimleriyle alınır. Gerçek parayla satın alma, şans kutusu veya reklam yoktur.'), tabs, list);
    return panel;
  }

  private async leaderboard(period: 'all' | 'weekly'): Promise<HTMLElement> {
    const data = await api.leaderboard(period);
    const panel: HTMLElement = h('div', { class: 'panel' }, this.back(), h('h2', {}, 'Sıralama'),
      h('div', { class: 'tabs' }, ...(['all', 'weekly'] as const).map((p) => h('button', { class: p === period ? 'on' : '', onclick: async () => panel.replaceWith(await this.leaderboard(p)) }, p === 'all' ? 'Tüm zamanlar' : 'Bu hafta'))),
      h('ol', { class: 'board', 'data-testid': 'leaderboard' }, ...data.entries.map((e) => h('li', { class: e.me ? 'me' : '' }, h('span', {}, `#${e.rank}`), h('span', {}, e.name), h('b', {}, fmt(e.score))))),
      data.entries.length === 0 ? h('p', { class: 'muted' }, 'Henüz doğrulanmış skor yok. İlk sen ol!') : null,
      h('p', { class: 'muted' }, data.myRank ? `Senin sıran: #${data.myRank} (${fmt(data.myScore)})` : 'Sıralamaya girmek için bir gösteri oyna.'));
    return panel;
  }

  private async daily(): Promise<HTMLElement> {
    const d = await api.daily();
    const claim = async () => {
      try {
        const r = await api.claimDaily();
        sfx.reward();
        toast(`+${r.reward.credits} 🪙${r.reward.gems ? ` +${r.reward.gems} 💎` : ''}`, 'reward');
        await this.refresh();
        this.show('daily');
      } catch (e) { toast(errText(e), 'error'); }
    };
    const current = d.canClaim ? d.nextCycleDay! : ((d.streak - 1) % 7) + 1;
    return h('div', { class: 'panel' }, this.back(), h('h2', {}, 'Günlük ödül'),
      h('p', { class: 'muted' }, `Seri: ${d.streak} gün. Bir gün kaçırırsan serin bir kez korunur; daha uzun arada hafta baştan başlar — hiçbir şey kaybetmezsin.`),
      h('div', { class: 'days' }, ...d.rewards.map((r, i) => h('div', { class: `day${i + 1 < current || (!d.canClaim && i + 1 === current) ? ' got' : ''}${d.canClaim && i + 1 === current ? ' today' : ''}` },
        h('small', {}, `${i + 1}. gün`), h('b', {}, `🪙${r.credits}`), r.gems ? h('span', {}, `💎${r.gems}`) : null))),
      d.canClaim ? h('button', { class: 'primary', 'data-testid': 'claim-daily', onclick: claim }, 'Al') : h('p', { class: 'muted' }, 'Yarın tekrar gel (UTC).'));
  }

  private async missions(): Promise<HTMLElement> {
    const { missions } = await api.missions();
    return h('div', { class: 'panel' }, this.back(), h('h2', {}, 'Günlük görevler'),
      h('div', { class: 'list' }, ...missions.map((m) => h('div', { class: 'row' },
        h('div', {}, h('b', {}, m.label), h('div', { class: 'progress' }, h('i', { style: `width:${Math.round((m.progress / m.target) * 100)}%` })), h('small', { class: 'muted' }, `${m.progress}/${m.target} · 🪙${m.reward}`)),
        m.claimed ? h('span', { class: 'muted' }, '✔') : h('button', { disabled: !m.completed, onclick: async () => {
          try { await api.claimMission(m.key); sfx.reward(); toast(`+${m.reward} 🪙`, 'reward'); await this.refresh(); this.show('missions'); } catch (e) { toast(errText(e), 'error'); }
        } }, 'Al')))),
      h('p', { class: 'muted small' }, 'Görevler her gün 00:00 UTC’de yenilenir.'));
  }

  private async profileView(): Promise<HTMLElement> {
    const inv = await api.inventory();
    const p = this.profile!;
    return h('div', { class: 'panel' }, this.back(), this.bar(), h('h2', {}, p.displayName),
      h('dl', { class: 'stats' },
        h('dt', {}, 'Seviye'), h('dd', {}, String(p.level)),
        h('dt', {}, 'Hayran'), h('dd', {}, fmt(p.fans)),
        h('dt', {}, 'En iyi skor'), h('dd', {}, fmt(p.bestScore)),
        h('dt', {}, 'Sıra'), h('dd', {}, p.rank ? `#${p.rank}` : '—'),
        h('dt', {}, 'Gösteri'), h('dd', {}, fmt(p.totalRuns))),
      h('h3', {}, 'Envanter'),
      h('div', { class: 'list' }, ...inv.items.map((i) => h('div', { class: 'row' }, h('b', {}, i.name), h('small', { class: 'muted' }, i.kind === 'skin' ? 'lamba' : 'kukla'),
        i.id === inv.loadout.skin || i.id === inv.loadout.character ? h('span', { class: 'muted' }, 'Kullanımda')
          : h('button', { onclick: async () => { await api.loadout(i.kind === 'skin' ? { skin: i.id } : { character: i.id }); await this.refresh(); this.show('profile'); } }, 'Kullan')))),
      h('h3', {}, 'Geliştirmeler'),
      h('p', { class: 'muted' }, inv.upgrades.length ? inv.upgrades.map((u) => `${u.id === 'tolerance' ? 'Sabit Eller' : 'Bis!'} Sv ${u.level}`).join(' · ') : 'Henüz yok — Mağaza’ya bak.'));
  }

  private levels(): HTMLElement {
    return h('div', { class: 'panel' }, this.back(), h('h2', {}, 'Perdeler'),
      h('p', { class: 'muted' }, `Her ${ROUNDS_PER_LEVEL} kalıpta yeni bir perde başlar: lamba hızlanır, salınım düzensizleşir, süre kısalır. Her ${BOSS_EVERY}. perde bir boss: Rüzgâr kalıbı oynatır ve mükemmel aralığı daralır.`),
      h('div', { class: 'list' }, ...Array.from({ length: 10 }, (_, i) => {
        const lvl = i + 1;
        const boss = lvl % BOSS_EVERY === 0;
        return h('div', { class: `row${boss ? ' boss' : ''}` }, h('b', {}, `${lvl}. perde${boss ? ' — BOSS: Rüzgâr' : ''}`), h('small', { class: 'muted' }, `${(lvl - 1) * ROUNDS_PER_LEVEL}. kalıptan itibaren`));
      })),
      h('p', { class: 'muted' }, `En iyi skorun: ${fmt(this.profile?.bestScore ?? 0)}. Her gösteri 1. perdeden başlar.`));
  }

  private notifications(): HTMLElement {
    const on = this.profile?.settings.notifications;
    return h('div', { class: 'panel' }, this.back(), h('h2', {}, 'Gelen kutusu'),
      this.notes.length ? h('div', { class: 'list' }, ...this.notes.map((n) => h('button', { class: 'row', onclick: () => this.show(n.go) }, n.text)))
        : h('p', { class: 'muted' }, on ? 'Yeni bir şey yok.' : 'Hatırlatmalar kapalı.'),
      h('p', { class: 'muted small' }, on ? 'Hatırlatmalar açık (yalnızca oyun içinde).' : 'Ayarlar’dan hatırlatmaları açabilirsin. Oyun dışına bildirim gönderilmez.'));
  }

  private settings(): HTMLElement {
    const p = this.profile;
    const toggle = (key: 'sound' | 'haptics' | 'notifications', label: string) => h('label', { class: 'row' }, h('span', {}, label),
      h('input', { type: 'checkbox', 'data-testid': `toggle-${key}`, checked: p?.settings[key] ?? true, disabled: !p, onchange: async (e) => {
        const on = (e.target as HTMLInputElement).checked;
        if (key === 'sound') setSoundEnabled(on);
        if (key === 'haptics') setHapticsEnabled(on);
        try { const r = await api.updateSettings({ [key]: on }); if (p) p.settings = r.settings; await this.refreshNotes(); } catch (err) { toast(errText(err), 'error'); }
      } }));
    const email = h('input', { type: 'email', placeholder: 'e-posta', autocomplete: 'email' });
    const pass = h('input', { type: 'password', placeholder: 'şifre (en az 8 karakter)', autocomplete: 'current-password' });
    const auth = async (mode: 'register' | 'login') => {
      try {
        const r = mode === 'register' ? await api.register(email.value, pass.value) : await api.login(email.value, pass.value);
        await api.setToken(r.token);
        await this.refresh();
        toast(mode === 'register' ? 'Hesap kaydedildi' : 'Giriş yapıldı', 'reward');
        this.show('settings');
      } catch (e) { toast(errText(e), 'error'); }
    };
    return h('div', { class: 'panel' }, this.back(), h('h2', {}, 'Ayarlar'),
      toggle('sound', 'Ses efektleri'), toggle('haptics', 'Titreşim'), toggle('notifications', 'Hatırlatmalar'),
      h('h3', {}, 'Hesap'),
      p?.registered ? h('p', { class: 'muted' }, 'İlerlemen hesabına kaydediliyor.') : h('p', { class: 'muted' }, 'Misafir olarak oynuyorsun. İlerlemeni e-posta hesabıyla sakla:'),
      p?.registered ? null : h('div', { class: 'form' }, email, pass,
        h('button', { onclick: () => auth('register') }, 'Hesabı kaydet'), h('button', { onclick: () => auth('login') }, 'Giriş yap')),
      h('p', { class: 'muted small' }, 'Gölge Kuklacı’da reklam, gerçek parayla satın alma ve şans kutusu yoktur.'));
  }
}
