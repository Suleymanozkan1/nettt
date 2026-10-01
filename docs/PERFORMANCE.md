# Performance Measurements

## Headless Chromium probe (`scripts/perf-probe.mjs`)

Setup: production build (`vite preview`), 412×915 phone viewport (DPR 2.6, touch); 10 s of gameplay with a tap every 0.9 s; reduced motion, so the 3D menu is not loaded. Run: `node scripts/perf-probe.mjs http://localhost:4173/?debug=1` (`CPU_THROTTLE` defaults to 4).

| Metric | CPU ×1 | CPU ×4 (low-end emulation) |
|---|---|---|
| Startup to interactive home | — | 2.0 s (DOMContentLoaded 1.0 s, load 1.5 s) |
| Transferred (first load, no 3D menu) | — | 414 KB |
| Gameplay FPS | 38.4 | 26.7 |
| Frame time p50 / p95 / p99 | 33.3 / 33.4 / 33.4 ms | 33.4 / 50.1 / 50.1 ms |
| Frames > 50 ms (10 s) | 1 | 15 |
| Long tasks (10 s) | 3 | 17 |
| JS heap | 15 MB | 16 MB |

**Caveat:** headless Chromium renders WebGL with SwiftShader (on the CPU) and caps here at ~30 fps even unthrottled. These are pessimistic **relative** numbers, not real-device FPS.

On-device frame statistics come from the CI Android emulator job (`dumpsys gfxinfo`, artifact `android-emulator/gfxinfo.txt`).

## Bundle

| Chunk | Size | Gzip | Loaded |
|---|---|---|---|
| phaser | 1.2 MB | 332 KB | always |
| app (UI, colyseus.js, shared rules) | ~140 KB | ~45 KB | always |
| Menu3D (three + R3F + drei) | 1.15 MB | 319 KB | lazily, on menus only, skipped with reduced motion / no WebGL2 |
| admin | 6 KB | 2.5 KB | admin.html only |

## Runtime design choices

- **No image/audio assets:** silhouettes are vector polygons and SFX are WebAudio-synthesised.
- **Rendering:** one Graphics object is redrawn per frame; pop-up text objects are destroyed after their tween.
- **Battery:** `powerPreference: 'low-power'`; solo shows pause in the background; the 3D menu is unmounted during play.
