import Phaser from 'phaser';
import { Capacitor } from '@capacitor/core';
import { App as CapApp } from '@capacitor/app';
import { StageScene } from './game/StageScene';
import { App } from './ui/app';
import { parseInviteUrl } from '@stage/shared';
import { testReminder } from './lib/reminders';
import './styles.css';

const game = new Phaser.Game({
  type: Phaser.AUTO,
  parent: 'game',
  backgroundColor: '#120d0a',
  scale: { mode: Phaser.Scale.RESIZE, width: window.innerWidth, height: window.innerHeight },
  render: { antialias: true, powerPreference: 'low-power' },
  fps: { target: 60 },
  scene: [StageScene],
  banner: false,
});

const app = new App(document.getElementById('ui')!, game);
void app.boot();

const scene = () => game.scene.getScene('stage') as StageScene | null;

// Lifecycle: pause the run when the app is backgrounded so the clock (and battery use) stops.
function pauseForBackground(): void {
  const s = scene();
  // Live duels cannot be paused (the server clock keeps running), so only solo shows pause.
  const live = !!document.querySelector('[data-testid="opponents"]');
  if (!live && s?.sim && s.sim.state === 'active' && !s.paused && document.getElementById('ui')?.dataset.screen === 'hud') {
    s.paused = true;
    app.show('pause');
  }
}
document.addEventListener('visibilitychange', () => { if (document.hidden) pauseForBackground(); });
if (Capacitor.isNativePlatform()) {
  // Deep links: golgekuklaci://duel/<code> (validated; anything else is ignored).
  void CapApp.addListener('appUrlOpen', ({ url }) => {
    const code = parseInviteUrl(url);
    if (code) app.openInvite(code);
  });
  void CapApp.addListener('pause', pauseForBackground);
  void CapApp.addListener('backButton', () => {
    const screen = document.getElementById('ui')?.dataset.screen;
    if (screen === 'hud') pauseForBackground();
    else if (screen !== 'home') app.show('home');
    else void CapApp.minimizeApp();
  });
}

const pinSelftest = import.meta.env.VITE_PIN_SELFTEST as string | undefined;
if (pinSelftest) void import('./lib/pin-selftest').then((m) => m.runPinSelftest(pinSelftest.split(',')));

if (new URLSearchParams(location.search).has('debug')) {
  (window as unknown as { __stage: unknown }).__stage = {
    state: () => scene()?.debugState() ?? null,
    testReminder: (seconds: number) => testReminder(seconds),
    /** Resolved safe-area insets (top, bottom, left, right) as the layout uses them. */
    insets: () => {
      const probe = document.body.appendChild(document.createElement('div'));
      probe.style.cssText = 'position:absolute;visibility:hidden;padding:var(--sat) var(--sar) var(--sab) var(--sal)';
      const cs = getComputedStyle(probe);
      const out = [cs.paddingTop, cs.paddingBottom, cs.paddingLeft, cs.paddingRight];
      probe.remove();
      return out;
    },
  };
}
