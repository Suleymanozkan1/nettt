# CodeRabbit Final Review Report

Repository `suleymanozkan1/nettt` · Branch `claude/relaxed-hypatia-vx3zna` · Date 2026-10-01

## Result: CodeRabbit BLOCKED. A substitute review was run, all findings were fixed, and the final pass is clean

CodeRabbit itself could not be run in this environment:

| Check | Result |
|---|---|
| CodeRabbit CLI | Not installed (`which coderabbit` → not found) |
| CodeRabbit GitHub App | No pull request exists (none was requested), so the App had nothing to review |

Instead, the independent `code-review` tool of this Claude Code session was run three times over the committed diffs. **These are not CodeRabbit results.**

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

## Re-run after changes

| Step | Result |
|---|---|
| `pnpm lint` | PASS |
| `pnpm typecheck` | PASS |
| `pnpm test` | PASS (21 shared + 6 game + 20 API = 47) |
| `pnpm build` | PASS |
| `pnpm e2e` (Playwright) | PASS 4/4 (mobile touch + desktop mouse) |

## How to get a real CodeRabbit review

Install the CodeRabbit GitHub App on the repository and open a pull request from this branch (or install the CodeRabbit CLI and run it on the branch).
