import Phaser from 'phaser';
import {
  RunSim, WALL_Y, WORLD_WIDTH, isBossLevel, perfectError, X_TOLERANCE, SCALE_TOLERANCE,
  type RoundView, type RunInput, type RunParams, type SimEvent,
} from '@stage/shared';
import { sfx } from '../lib/audio';
import { haptic } from '../lib/haptics';
import { placeShape, SHAPE_NAMES_TR } from './shapes';

export interface RunCallbacks {
  onRound(ev: Extract<SimEvent, { type: 'fit' | 'timeout' }>): void;
  onBoss(level: number): void;
  onDead(): void;
}

const WORLD_H = 640;
const SHAPE_R = 46; // shadow half-size at scale 1
const PIVOT = { x: WORLD_WIDTH / 2, y: 0 };
const ROPE = 110;
const PUPPET = { x: WORLD_WIDTH / 2, y: 470 };
const WALL = { top: 40, bottom: 440 };
const VENUES = [0x5a4632, 0x3f4a5c, 0x5c3f4f, 0x3f5c52, 0x4d1f1f];
const AUDIENCE = 14;

type Flash = { view: RoundView; color: number; until: number };

/** Renders and drives a RunSim. All game rules live in @stage/shared; this class is presentation + input. */
export class StageScene extends Phaser.Scene {
  sim: RunSim | null = null;
  inputs: RunInput[] = [];
  clock = 0;
  paused = false;
  private lightColor = 0xffc46b;
  private puppetColor = 0xff8c42;
  private cb: RunCallbacks | null = null;
  private world!: Phaser.GameObjects.Container;
  private wall!: Phaser.GameObjects.Rectangle;
  private gfx!: Phaser.GameObjects.Graphics;
  private shapeLabel!: Phaser.GameObjects.Text;
  private audience: Phaser.GameObjects.Arc[] = [];
  private flash: Flash | null = null;
  private deadNotified = false;
  private frameAt = 0;

  constructor() { super('stage'); }

  create(): void {
    this.world = this.add.container(0, 0);
    this.wall = this.add.rectangle(WORLD_WIDTH / 2, (WALL.top + WALL.bottom) / 2, WORLD_WIDTH, WALL.bottom - WALL.top, VENUES[0]!);
    const floor = this.add.rectangle(WORLD_WIDTH / 2, (WALL.bottom + WORLD_H) / 2, WORLD_WIDTH + 80, WORLD_H - WALL.bottom, 0x2a1a10);
    const curtainL = this.add.rectangle(-10, WORLD_H / 2 - 40, 50, WORLD_H, 0x8b1e2d);
    const curtainR = this.add.rectangle(WORLD_WIDTH + 10, WORLD_H / 2 - 40, 50, WORLD_H, 0x8b1e2d);
    const valance = this.add.rectangle(WORLD_WIDTH / 2, -30, WORLD_WIDTH + 80, 40, 0x6e1622);
    this.gfx = this.add.graphics();
    this.shapeLabel = this.add.text(WORLD_WIDTH / 2, WALL.bottom - 24, '', { fontFamily: 'system-ui, sans-serif', fontSize: '16px', color: '#ffffff' }).setOrigin(0.5).setAlpha(0.7);
    this.world.add([this.wall, floor, this.gfx, this.shapeLabel, curtainL, curtainR, valance]);
    for (let i = 0; i < AUDIENCE; i++) {
      const head = this.add.circle(0, 0, 13, 0x0d0a14);
      this.audience.push(head);
      this.world.add(head);
    }
    this.input.on('pointerdown', () => this.tap());
    this.input.keyboard?.on('keydown-SPACE', () => this.tap());
    this.scale.on('resize', () => this.layout());
    this.layout();
    this.cameras.main.setBackgroundColor(0x120d0a);
  }

  private layout(): void {
    const { width, height } = this.scale;
    // Keep the stage below the DOM HUD (level, lives, score) so nothing overlaps.
    const hudTop = Math.min(150, height * 0.17);
    const avail = height - hudTop - 8;
    const s = Math.min(width / (WORLD_WIDTH + 100), avail / (WORLD_H + 60));
    this.world.setScale(s);
    this.world.setPosition((width - WORLD_WIDTH * s) / 2, hudTop + (avail - WORLD_H * s) / 2 + 50 * s);
    this.audience.forEach((a, i) => a.setPosition((WORLD_WIDTH / (AUDIENCE - 1)) * i, 600 + (i % 2) * 14));
  }

  startRun(seed: number, params: RunParams, lightColor: number, puppetColor: number, cb: RunCallbacks): void {
    this.sim = new RunSim(seed, params);
    this.inputs = [];
    this.clock = 0;
    this.paused = false;
    this.deadNotified = false;
    this.flash = null;
    this.frameAt = performance.now();
    this.lightColor = lightColor;
    this.puppetColor = puppetColor;
    this.cb = cb;
    this.wall.setFillStyle(VENUES[0]!);
  }

  /**
   * Exact input time: the run clock at the last frame plus the real time since that frame. Without this,
   * taps would be quantised to frame boundaries and count late on slow (low-FPS) devices.
   */
  private now(): number {
    return Math.round(this.clock + (this.paused ? 0 : Math.max(0, performance.now() - this.frameAt)));
  }

  tap(): void {
    const sim = this.sim;
    if (!sim || this.paused) return;
    const t = this.now();
    this.process(sim.advance(t));
    if (!sim.canTap(t)) return;
    const ev = sim.tap(t);
    if (!ev) return;
    this.inputs.push({ t, k: 'tap' });
    this.process([ev]);
  }

  revive(): boolean {
    const sim = this.sim;
    if (!sim) return false;
    const t = this.now();
    if (!sim.revive(t)) return false;
    this.inputs.push({ t, k: 'revive' });
    this.deadNotified = false;
    return true;
  }

  quit(): void {
    const sim = this.sim;
    if (!sim || sim.state === 'dead') return;
    const t = this.now();
    // Resolve due timeouts first, exactly like the server replay; if they end the show, there is nothing to quit.
    this.process(sim.advance(t));
    if (sim.state === 'active' && sim.quit(t)) this.inputs.push({ t, k: 'quit' });
    this.deadNotified = true;
  }

  private process(events: SimEvent[]): void {
    for (const ev of events) {
      if (ev.type === 'fit') {
        const color = ev.grade === 'perfect' ? 0xffe066 : ev.grade === 'good' ? this.lightColor : 0xff4d4d;
        this.flash = { view: ev.view, color, until: this.clock + 600 };
        if (ev.grade === 'perfect') { sfx.perfect(ev.combo); haptic.light(); this.cheer(true); this.popText(ev.combo > 1 ? `MÜKEMMEL x${ev.combo}` : 'MÜKEMMEL', '#ffe066'); }
        else if (ev.grade === 'good') { sfx.place(); this.cheer(false); this.popText('İYİ', '#ffffff'); }
        else { sfx.miss(); haptic.fail(); this.cameras.main.shake(200, 0.008); this.popText('KAÇTI', '#ff6b6b'); }
        this.levelFx(ev);
        this.cb?.onRound(ev);
      } else if (ev.type === 'timeout') {
        sfx.miss();
        haptic.fail();
        this.popText('SÜRE DOLDU', '#ff6b6b');
        this.levelFx(ev);
        this.cb?.onRound(ev);
      }
    }
  }

  private levelFx(ev: { levelUp: boolean; bossStart: boolean; level: number }): void {
    if (!ev.levelUp) return;
    sfx.levelUp();
    this.wall.setFillStyle(isBossLevel(ev.level) ? VENUES[4]! : VENUES[(ev.level - 1) % 4]!);
    if (ev.bossStart) { sfx.boss(); haptic.heavy(); this.cb?.onBoss(ev.level); }
  }

  private popText(text: string, color: string): void {
    const label = this.add.text(WORLD_WIDTH / 2, WALL_Y - 150, text, { fontFamily: 'system-ui, sans-serif', fontSize: '28px', fontStyle: 'bold', color, stroke: '#000', strokeThickness: 5 }).setOrigin(0.5);
    this.world.add(label);
    this.tweens.add({ targets: label, y: WALL_Y - 190, alpha: 0, duration: 800, onComplete: () => label.destroy() });
  }

  private cheer(big: boolean): void {
    this.audience.forEach((a, i) => {
      if (!big && i % 3) return;
      this.tweens.add({ targets: a, y: a.y - (big ? 18 : 10), duration: 110, yoyo: true, repeat: big ? 1 : 0, delay: (i % 5) * 30 });
    });
  }

  update(): void {
    const sim = this.sim;
    const g = this.gfx;
    g.clear();
    if (!sim) { this.drawLamp(0); this.drawPuppet('star'); this.shapeLabel.setText(''); return; }
    // The run clock follows real elapsed time (not Phaser's smoothed delta) so input times stay exact.
    const now = performance.now();
    if (!this.paused) this.clock += now - this.frameAt;
    this.frameAt = now;
    this.process(sim.advance(Math.round(this.clock)));

    if (sim.state === 'dead') {
      if (!this.deadNotified) { this.deadNotified = true; this.time.delayedCall(700, () => this.cb?.onDead()); }
      this.drawLamp(0);
      this.shapeLabel.setText('');
      return;
    }
    const live = sim.view(this.clock);
    const frozen = this.flash && this.clock < this.flash.until ? this.flash : null;
    const v = frozen ? frozen.view : live;

    // Hole (target silhouette) on the wall.
    const holePts = placeShape(v.shape, v.holeX, WALL_Y, SHAPE_R * v.holeScale);
    if (frozen) { g.fillStyle(frozen.color, 0.55); g.fillPoints(holePts, true); }
    g.lineStyle(4, frozen ? frozen.color : (v.boss ? 0xff4d4d : this.lightColor), live.ready || frozen ? 1 : 0.3);
    g.strokePoints(holePts, true, true);

    // Light cone + shadow.
    this.drawLamp(v.theta);
    if (live.ready || frozen) {
      g.fillStyle(0x000000, 0.82);
      g.fillPoints(placeShape(v.shape, v.shadowX, WALL_Y, SHAPE_R * v.shadowScale), true);
    }
    this.drawPuppet(v.shape);
    this.shapeLabel.setText(SHAPE_NAMES_TR[v.shape]);

    // Round timer bar across the top of the wall.
    if (live.ready && !frozen) {
      g.fillStyle(0x000000, 0.35);
      g.fillRect(20, WALL.top + 8, WORLD_WIDTH - 40, 8);
      g.fillStyle(live.timeLeft < 0.3 ? 0xff4d4d : this.lightColor, 1);
      g.fillRect(20, WALL.top + 8, (WORLD_WIDTH - 40) * live.timeLeft, 8);
    }
  }

  private drawLamp(theta: number): void {
    const g = this.gfx;
    const lx = PIVOT.x + ROPE * Math.sin(theta);
    const ly = PIVOT.y + ROPE * Math.cos(theta);
    g.fillStyle(this.lightColor, 0.1);
    g.fillTriangle(lx, ly, Math.max(-20, lx - 240), WALL.bottom, Math.min(WORLD_WIDTH + 20, lx + 240), WALL.bottom);
    g.lineStyle(3, 0x222222, 1);
    g.lineBetween(PIVOT.x, PIVOT.y - 40, lx, ly);
    g.fillStyle(this.lightColor, 0.35);
    g.fillCircle(lx, ly, 26);
    g.fillStyle(this.lightColor, 1);
    g.fillCircle(lx, ly, 13);
  }

  private drawPuppet(shape: RoundView['shape']): void {
    const g = this.gfx;
    g.lineStyle(5, 0x6b4a2f, 1);
    g.lineBetween(PUPPET.x, PUPPET.y + 18, PUPPET.x, PUPPET.y + 90);
    g.fillStyle(this.puppetColor, 1);
    g.fillPoints(placeShape(shape, PUPPET.x, PUPPET.y, 22), true);
  }

  /** Read-only state for automated UI tests (enabled with ?debug=1). */
  debugState(): { clock: number; state: string; score: number; fits: number; misses: number; lives: number; taps: number; msToPerfect: number | null } | null {
    const sim = this.sim;
    if (!sim) return null;
    let msToPerfect: number | null = null;
    const limit = perfectError(sim.level, sim.params) * 0.8;
    for (let dt = 0; dt < 6000 && sim.state === 'active'; dt++) {
      const t = this.clock + dt;
      const v = sim.view(t);
      if (!v.ready) { if (dt > 0 && sim.canTap(Math.round(this.clock))) break; continue; }
      if (Math.hypot((v.shadowX - v.holeX) / X_TOLERANCE, (v.shadowScale - v.holeScale) / SCALE_TOLERANCE) <= limit) { msToPerfect = dt; break; }
    }
    return { clock: this.clock, state: sim.state, score: sim.score, fits: sim.fits, misses: sim.misses, lives: sim.lives, taps: this.inputs.filter((i) => i.k === 'tap').length, msToPerfect };
  }
}

