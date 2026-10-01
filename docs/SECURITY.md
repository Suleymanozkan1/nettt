# Security checks

| Check | Where | Result |
|---|---|---|
| Dependency audit | CI `checks`: `pnpm audit --prod --audit-level=moderate` | clean, with the exceptions below |
| DAST | CI `checks`: OWASP ZAP baseline against the API (`/health`) and the game page | report in the `playwright` artifact (`zap/`) |
| Certificate pinning | CI `android-device` and `ios` jobs | see docs/CERT_PINNING.md |
| Unit/integration security tests | `apps/api/test` | auth, PoW, CSRF Origin guard, admin cookie, rate limits, replay lock, run validation |

## Overrides (package.json `pnpm.overrides`)

- `nanoid` 3.x → ≥ 3.3.18 (used by the build toolchain)
- `deepmerge-ts` → 8.x (used by `@prisma/config`). `prisma validate`, `generate` and `migrate` still work.

## Accepted advisories (package.json `pnpm.auditConfig.ignoreGhsas`)

`@colyseus/core` 0.16 depends on **nanoid 2.1.11** (`import nanoid from "nanoid"`). Forcing 3.x breaks it, because 3.x has no default export. The advisories below are for nanoid < 3.3.x:

- GHSA-mwcw-c2x4-8c55: predictable output for **non-integer** sizes
- GHSA-28wg-ghj8-5hjv: non-secure generators can loop with a **negative** size
- GHSA-2v37-7h3g-55p8: custom generators can loop when the size is **zero**
- GHSA-xwg4-73v4-xw9w: **integer overflow** of the size

Colyseus calls nanoid only through `generateId(length = 9)`, always with the default size of 9: room ids, session ids, process ids and reconnection tokens. That is the secure default generator with a fixed positive integer, so none of these code paths can be reached. Revisit this when Colyseus moves to nanoid 3+.

## Not done

There has been no external penetration test, which needs an independent tester.
