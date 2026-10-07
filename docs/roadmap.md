# Roadmap

This is the log of everything we have **deliberately deferred**. Each entry records what was postponed, when, and why, so the project's evolution can be traced from the decision to defer through to delivery.

**Lifecycle of an entry:** `deferred` → `planned` (turned into a spec with `/to-spec`) → `ticketed` (broken into GitHub Issues with `/to-tickets`) → `done` (link the PRs, and the ADR if any). Entries are never deleted. When the work ships they are marked done and linked.

## Waves (priority order)

Items within a wave are listed in the order they should be picked up.

| Wave | Theme | Items in order | Why this order |
|---|---|---|---|
| **1** ✅ | **Greenfield v1**: build it, all tests green, CI. **Done:** spec 0001 implemented (#3–#8, #14, #16) | *(the core: no roadmap items)* | Nothing else makes sense until the core works and is tested |
| **2** | **Production readiness** | R11 → R12 → R6 → R17 | Package it first (R11) so everything after runs the same way everywhere. Then make it operable with health checks, logs and config (R12). Then close the one security gap before it is exposed anywhere (R6) |
| **3** | **Reliability & scalability, measured** | R13 → R5 → R14 → R15 → R7 | **Measure before changing**: a load-test baseline (R13) gives the numbers that justify the Postgres migration (R5). Failure testing (R14) runs against the real production setup. Scaling out (R15) needs Postgres first, and only then does code generation need revisiting (R7) |
| **4** | **Feature evolution** (brownfield) | R16 (when released) → R10 → R2 → R1 → R3 → R4 → R9 | The clickstream (R10) comes first because click counts (R2) can be derived from it. Then the other features. R9 only if needed |

Waves 2–4 are each started deliberately by the user. Feature items from wave 4 may be pulled earlier if the user chooses.

## Items

| # | Item | Wave | Deferred on | Why deferred | Status | Links |
|---|---|---|---|---|---|---|
| R1 | Custom aliases (`/my-link`) | 4 | 2026-10-07 | Keep v1 to the core; adding it to a running system is brownfield evidence | deferred | |
| R2 | Click counts per link | 4 | 2026-10-07 | Same as R1. Each link already has its own code (ADR 0003), so counts will be per link | deferred | |
| R3 | Expiring links | 4 | 2026-10-07 | Same as R1 | deferred | |
| R4 | Edit / delete a link | 4 | 2026-10-07 | Not needed to prove the core flow | deferred | |
| R5 | Migrate storage SQLite → PostgreSQL | 3 | 2026-10-07 | No load or concurrency need yet. The storage interface keeps the move contained | deferred | ADR 0002 (to be superseded) |
| R6 | URL rule: block private/internal addresses (`localhost`, `10.x`, `192.168.x`) and verify that DNS resolves | 2 | 2026-10-07 | v1 ships basic rules only; must land before any real exposure | deferred | ADR 0004 |
| R7 | Revisit code generation if we scale beyond one node | 3 | 2026-10-07 | A key pool or distributed IDs would be overkill for a single node | deferred | ADR 0003 |
| R8 | User accounts and link ownership | — | 2026-10-07 | Not part of this project's goals | won't do (for now) | |
| R9 | Config-driven URL rules (enable/tune rules from a file without a deploy) | 4 | 2026-10-07 | Adopt only when a no-deploy rule change is actually needed (ADR 0004, option B) | deferred (conditional) | ADR 0004 |
| R10 | Clickstream / audit pipeline: emit an event for every successful redirect (code, timestamp, referrer, user agent) for auditing and analytics | 4 | 2026-10-07 | Builds on the core redirect; possible only because redirects are 302 and every click passes through us (ADR 0005). Relates to R2 (click counts could be derived from it) | deferred | ADR 0005 |
| R11 | **Dockerize**: multi-stage Dockerfile (Maven build stage, JRE 25 runtime stage), `docker compose` for local run, image built and tested in CI | 2 | 2026-10-07 | Production-readiness wave, after v1 is green | deferred | |
| R12 | **Operability basics**: liveness/readiness endpoints, structured JSON logs with request IDs, all config from environment (`BASE_URL` etc.), graceful shutdown, first `docs/runbook.md` | 2 | 2026-10-07 | Same as R11 | deferred | |
| R13 | **Load & performance testing**: scripted load tests on the redirect hot path (ADR 0005) and create path; set SLOs (e.g. p95 redirect latency) and record a baseline | 3 | 2026-10-07 | Reliability wave. Gives the evidence that drives R5 and R15 | deferred | |
| R14 | **Failure / resilience testing**: behaviour when the DB is down or slow, disk is full, or the container restarts; verify errors, health status and recovery; incident write-ups for anything found | 3 | 2026-10-07 | Reliability wave, after R5 so it tests the production setup | deferred | |
| R15 | **Horizontal scaling**: several app instances behind a load balancer, verified under R13's load tests | 3 | 2026-10-07 | Needs shared storage (R5) first; triggers R7 | deferred | |
| R16 | **Upgrade Spring Boot 4.1 → 4.2** (due Nov 2026) as a deliberate, tested dependency upgrade | 4 | 2026-10-07 | Not released yet; a minor upgrade is better done as its own traceable change | deferred | ADR 0001 |
| R17 | **Code coverage reporting** (JaCoCo) in CI | 2 | 2026-10-07 | A coverage number on a handful of tests says little; more useful once the suite has grown | deferred | Plan 0001 |
