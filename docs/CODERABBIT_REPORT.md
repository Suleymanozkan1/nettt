# CodeRabbit Final Review Report

Repository `suleymanozkan1/nettt` · Branch `claude/relaxed-hypatia-vx3zna` · Date 2026-10-01

## Result: CodeRabbit BLOCKED. A substitute review was run, all findings were fixed, and the final pass is clean

CodeRabbit itself could not be run in this environment:

| Check | Result |
|---|---|
| CodeRabbit CLI | Not installed (`which coderabbit` → not found) |
| CodeRabbit GitHub App | No pull request exists (none was requested), so the App had nothing to review |

Instead, the independent `code-review` tool of this Claude Code session was run over the committed diffs: three times in round 1, four times in round 2 and three times in round 3. **These are not CodeRabbit results.**

## Pass 1: `HEAD~1..HEAD` of the game commit (high effort)

| # | File | Finding | Verdict | Fix | Regression test |
|---|---|---|---|---|---|
| 1 | apps/api/src/app.ts | `trustProxy: true` let clients spoof `X-Forwarded-For` and bypass rate limits and the guest-per-IP cap | Confirmed | `TRUST_PROXY` env, default `false` | XFF spoof → still 429 |
| 2 | apps/api/src/ledger.ts | After an automatic liability trip, an admin could not resume rewards for 24h | Confirmed | Admin decision changes the counting window | auto-trip → admin resume → claim OK |
| 3 | apps/api/src/routes/runs.ts | Abandoned runs counted toward cheat flagging | Confirmed | Exclude `rejectReason = 'abandoned'` | 3 abandoned + 1 rejected → not flagged |
| 4 | apps/api/src/routes/runs.ts | `bestScore` / mission progress lost updates under concurrent finishes | Plausible | Conditional `updateMany` (bestScore only rises); atomic increments | covered by existing best-score tests |
| 5 | apps/game/src/game/StageScene.ts | `quit()` did not resolve due timeouts first, unlike the server, so a valid run could be rejected | Plausible | `advance(t)` before `quit` | sim test: quit after deadline |
| 6 | packages/shared/src/sim.ts | Dying on a boss act's last round still paid the boss reward | Confirmed | Count a boss clear only if lives > 0 | sim test |

## Pass 2: the fix commit (medium effort)

| # | File | Finding | Fix |
|---|---|---|---|
| 7 | apps/api/src/ledger.ts | The admin override disabled the liability check entirely for 24h | The check keeps running and counts only grants since the admin decision; test asserts it re-trips |

## Pass 3: final fix (low effort)

**No bugs found.** Design note recorded in the final report: each admin "resume" restarts the counting window, so up to about 2× the limit can be granted within a rolling 24h.

## Round 2 (duel, challenge, events, admin, mobile, 3D menu)

### Pass 4: `a410172..HEAD` (high effort)

| # | File | Finding | Fix | Regression test |
|---|---|---|---|---|
| 8 | apps/api/src/realtime/DuelRoom.ts | A seat reserved just before the countdown ended could join after `start()` with no simulation, crashing `tick()` | Reject auth/join once playing; null-safe seats | late join rejected |
| 9 | apps/game/src/ui/app.ts | The delayed death callback could overwrite the duel result screen | `stop()` cancels the timer; handler ignores finished duels | — (UI) |
| 10 | apps/game/src/ui/app.ts | "Vazgeç" during a pending join left the player in the room | Attempt counter; a late-resolving join leaves | — (UI) |
| 11 | apps/api/src/realtime/DuelRoom.ts | Parallel joins of one account could take two seats (self-farming) | Synchronous pending guard before any await | parallel join test |
| 12 | apps/api/src/realtime-server.ts | Unknown HTTP paths hung | 404 for non-matchmake paths | `/nope` → 404 |
| 13 | apps/api/src/routes/runs.ts | Parallel challenge starts could exceed 5/day | `pg_advisory_xact_lock` per user | 8 parallel → exactly 5 OK |

### Pass 5: fix commit (medium effort)

| # | Finding | Fix |
|---|---|---|
| 14 | The pending guard leaked when the socket closed during the auth DB lookup | session→user map, cleared in `onLeave` |

### Pass 6: low effort

| # | Finding | Fix |
|---|---|---|
| 15 | Pass 5's fix never ran: Colyseus 0.16 skips `onLeave` in that path | Check `ClientState.LEAVING` at the end of `onAuth` and release there |

### Pass 7: low effort

**No bugs found.**

## Round 3 (invites, admin cookie, PoW, TLS, cluster, economy, CI device jobs)

### Pass 8: round-3 diff (high effort)

| # | Finding | Fix |
|---|---|---|
| 16 | `duelJoin` (invite) was not cleared when going home, so a later "Düello" re-joined the old private room | Reset on home |
| 17 | Daily pacing counters were read without a lock, so parallel finishes could both pay full credits | Read under a per-player `pg_advisory_xact_lock` |
| 18 | `decodeURIComponent` on a malformed invite link threw and broke start-up | Wrapped in try/catch → `null` |
| 19 | Opening an invite during a solo show left the show running underneath | The invite stops the running show first |
| 20 | `trustProxy: true` let any client spoof `X-Forwarded-For` and dodge per-IP limits; compose published API/realtime on all interfaces | TRUST_PROXY accepts a CIDR list (private ranges by default); ports bound to 127.0.0.1 |

### Pass 9: CI scripts (medium effort)

| # | Finding | Fix |
|---|---|---|
| 21 | Deep-link smoke step never failed (an error was only logged) | `grep -qi error → exit 1` |
| 22 | `device.tap` takes a selector, not coordinates, so no OS touch was sent | adb `input tap x y` |

### Pass 10: CI run follow-ups

| # | Finding | Fix |
|---|---|---|
| 23 | `run-as sh -c` hung the emulator job | Per-file `run-as ls` / `exec-out cat` loop + adb timeouts |
| 24 | Blind taps end the show (3 misses), so the pause button the script waited for no longer existed | Finish via the game-over "Sonuçlar" button when the show is already over |

## Re-run after changes

| Step | Result |
|---|---|
| `pnpm lint` | PASS |
| `pnpm typecheck` | PASS |
| `pnpm test` | PASS (34 shared + 6 game + 42 API = 82) |
| `pnpm build` | PASS (game + admin pages, 3D chunk) |
| `pnpm e2e` (Playwright) | PASS 14, 4 desktop-only skips (incl. two-browser duel, invite, revive, admin panel) |

## How to get a real CodeRabbit review

Install the CodeRabbit GitHub App on the repository and open a pull request from this branch (or install the CodeRabbit CLI and run it on the branch).
