---
status: accepted
date: 2026-10-07
---

# Scaling path: staged, and each stage triggered by measured evidence

Today the shortener runs as **one instance on SQLite**, with in-app rate limits (ADR 0016) and an in-process Click queue (ADR 0012). That is the right size now, and building multi-node infrastructure ahead of need would cost time and add moving parts. A reviewer, and the next engineer, still need to know **how it scales and when**. This ADR records the path. It is documented, not built.

## The path

| Stage | Change | **Trigger** (from load tests, R13, or production metrics, ADR 0015) | Depends on |
|---|---|---|---|
| **0 (today)** | One instance; SQLite; in-app rate limits; in-process Click queue | — | — |
| **1** | **SQLite → PostgreSQL** | Sustained write contention (`SQLITE_BUSY`), the p95 Redirect latency SLO breached at the measured load, or a need for availability during deploys | Link Store interface (ADR 0002); a contract test suite that both stores must pass; Flyway migrations ported |
| **2** | **Several stateless instances** behind a load balancer | One instance can't meet the latency SLO at peak, or zero-downtime deploys are required | Stage 1 (shared database); rate limits moved to the edge or a shared store (ADR 0016); per-instance Click queues writing to the shared database |
| **3** | **Redirect cache** (in-process per instance, or shared) | Database reads dominate Redirect latency | Links are immutable today, so caching is safe. Edit/delete (R4) would add cache invalidation |
| **4** | **Clicks to an event bus** (Kafka / Redis Streams) | Click write volume exceeds what batched database writes sustain | `ClickRecorder` interface (ADR 0012) |

Stages are taken **in order and only when their trigger fires**. Each one gets its own spec, tickets and, where needed, an ADR that supersedes the relevant earlier decision (e.g. ADR 0002 at stage 1).

**Short Code generation needs no change at any stage.** Random codes with the uniqueness check enforced by the *database* (ADR 0003, ADR 0012) are already safe across instances: two instances drawing the same code cannot both save it, and the loser retries. Roadmap item R7 is closed on this basis.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Staged path; each stage triggered by a measured signal; documented now, built when triggered** | A defensible scaling story at no build cost; every step has a trigger and named dependencies; infrastructure arrives only when evidence demands it | The path is a plan, not proven in production; triggers depend on metrics being watched | **Chosen** |
| B | Move to PostgreSQL (and multi-instance) now, "to be ready" | Closer to a typical production setup | Infrastructure and migration effort without evidence; time taken from current priorities | Rejected |
| C | Design for a distributed key-value store (DynamoDB, Cassandra) at internet scale | Massive scale ceiling | Far beyond realistic load; shapes the whole system around a need that doesn't exist | Rejected |

**In short:** know the path, put a trigger on every step, and take the next step only when the measurements say so.

## Consequences

- The load-test baseline (R13) must measure the stage-1 and stage-2 triggers (p95/p99 Redirect latency, write contention) so they are not guesses.
- Roadmap items R5 and R15 follow this ADR's stages and triggers. R7 is closed: no change needed.
- Anything that adds per-instance state (caches, queues, limits) must name how it behaves at stage 2.
