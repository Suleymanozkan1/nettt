# Gölge Kuklacı (Shadow Puppeteer)

An original one-tap casual game. A lamp swings like a pendulum behind a puppet. The puppet's shadow slides
across the wall and grows as the lamp swings out. A glowing silhouette (cat, bird, moon…) appears on the
wall: **tap when the shadow fits it**.

- **Perfect** fit → combo (+3 + combo, capped at +10) and the audience cheers
- **Good** fit → 1–2 points, combo resets
- **Miss** or **timeout** → one spotlight (life) goes out. Three spotlights per show (more with the *Bis!* upgrade)
- Every 10 silhouettes a new **act**: faster lamp, less predictable swing, shorter timer
- Every 5th act is a **boss** — *the Wind* moves the silhouette and the perfect window shrinks
- One **revive** (*Bis!*) per show for 5 gems

There are **no ads, no real-money purchases, no loot boxes and no crypto/wallets**. Credits and gems are
earned only by playing.

## Architecture

```
packages/shared   Game rules: seeded RNG, RunSim (deterministic simulation), replayRun (server validator),
                  economy, missions, daily streak, zod request schemas. Used by client AND server.
apps/api          Fastify 5 + Prisma 6 (PostgreSQL) + Redis rate limiting. JWT auth (guest device id, email).
apps/game         Vite + Phaser 3 scene + DOM UI (Turkish). Capacitor 7 Android project in apps/game/android.
e2e               Playwright: real browser against real API + DB (Pixel 7 touch, desktop mouse).
```

**Server authority.** `POST /runs` returns a server-generated seed. The client records only input
timestamps (`tap`, `revive`, `quit`). `POST /runs/:id/finish` replays them with the same `RunSim`; score,
fans, credits and gems are computed on the server. Runs are rejected if an input is impossible, if the
simulated show is longer than the real time elapsed (speed hack), if the run was already submitted
(replay), or if a revive was not paid for. Three rejections in 24 h flag the account (hidden from the
leaderboard).

## Run locally

Requirements: Node 22, pnpm 10, PostgreSQL 16, Redis (optional; in-memory rate limit without it).

```sh
pnpm install
cp apps/api/.env.example apps/api/.env      # set JWT_SECRET (32+ chars) and DATABASE_URL
pnpm db:migrate
pnpm dev:api                                 # http://localhost:3000
pnpm dev:game                                # http://localhost:5173
```

Checks: `pnpm lint`, `pnpm typecheck`, `pnpm test` (API tests need a Postgres database
`stagestack_ci`, override with `TEST_DATABASE_URL`), `pnpm build`, `pnpm e2e`.

Docker: `JWT_SECRET=... docker compose up --build` → game on http://localhost:8080, API on :3000.

Android: `pnpm --filter @stage/game build && cd apps/game && npx cap sync android`, then
`cd android && ./gradlew assembleDebug` (needs Android SDK 35 + JDK 21). The API must be reachable over
HTTPS from a real device; set `VITE_API_URL` at build time.

Admin: `pnpm --filter @stage/api exec tsx --env-file=.env scripts/make-admin.ts <email>`, then use
`GET/POST /admin/economy`, `GET /admin/users/:id`, `GET /admin/transactions` (no admin web UI yet).

Backup: `DATABASE_URL=... ./scripts/backup-db.sh backups/`.
