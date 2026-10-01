/** Tiny WebAudio synth: no audio assets to download, works offline. */
let ctx: AudioContext | null = null;
let enabled = true;

export function setSoundEnabled(on: boolean): void { enabled = on; }

function ac(): AudioContext | null {
  if (!enabled) return null;
  if (!ctx) {
    const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return null;
    ctx = new Ctor();
  }
  if (ctx.state === 'suspended') void ctx.resume();
  return ctx;
}

function tone(freq: number, dur: number, type: OscillatorType = 'sine', gain = 0.15, slide = 0): void {
  const c = ac();
  if (!c) return;
  const osc = c.createOscillator();
  const g = c.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, c.currentTime);
  if (slide) osc.frequency.exponentialRampToValueAtTime(Math.max(40, freq + slide), c.currentTime + dur);
  g.gain.setValueAtTime(gain, c.currentTime);
  g.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + dur);
  osc.connect(g).connect(c.destination);
  osc.start();
  osc.stop(c.currentTime + dur);
}

const SCALE = [261.6, 293.7, 329.6, 392, 440, 523.3, 587.3, 659.3, 784, 880];

export const sfx = {
  place: () => tone(220, 0.08, 'triangle', 0.12),
  perfect: (combo: number) => tone(SCALE[Math.min(combo - 1, SCALE.length - 1)] ?? 880, 0.18, 'sine', 0.18),
  cut: () => tone(160, 0.12, 'square', 0.06, -60),
  miss: () => tone(300, 0.5, 'sawtooth', 0.12, -220),
  levelUp: () => { tone(523, 0.12); setTimeout(() => tone(659, 0.12), 110); setTimeout(() => tone(784, 0.2), 220); },
  boss: () => tone(110, 0.6, 'sawtooth', 0.1, 40),
  click: () => tone(660, 0.04, 'square', 0.05),
  reward: () => { tone(784, 0.1); setTimeout(() => tone(1046, 0.18), 90); },
};
