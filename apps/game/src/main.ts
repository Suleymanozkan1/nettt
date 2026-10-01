import Phaser from 'phaser';
import { Capacitor } from '@capacitor/core';
import { App as CapApp } from '@capacitor/app';
import { StageScene } from './game/StageScene';
import { App } from './ui/app';
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
  void CapApp.addListener('pause', pauseForBackground);
  void CapApp.addListener('backButton', () => {
    const screen = document.getElementById('ui')?.dataset.screen;
    if (screen === 'hud') pauseForBackground();
    else if (screen !== 'home') app.show('home');
    else void CapApp.minimizeApp();
  });
}

if (new URLSearchParams(location.search).has('debug')) {
  (window as unknown as { __stage: unknown }).__stage = { state: () => scene()?.debugState() ?? null };
}
