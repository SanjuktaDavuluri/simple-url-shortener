---
status: accepted
date: 2026-10-08
---

# SQLite runs in WAL mode with a busy timeout and a small connection pool

ADR 0002 chose SQLite for storage. Release 1 runs it with SQLite's default rollback journal and a single pooled connection (`spring.datasource.hikari.maximum-pool-size=1`). With one table and one writer (Link creation), that was enough.

Spec 0003 (R10, Clickstream) adds a second writer. The Click Recorder's background writer saves Clicks in batches (ADR 0012). Story 22 requires that Redirect lookups don't wait behind a Click batch being written, because the Redirect is the product's hot path (ADR 0005). With one connection, every Redirect lookup queues for the same connection the Click writer is holding. With the rollback journal, even a second connection can't read while a writer commits.

## Decision

- The SQLite database runs in **WAL (write-ahead log) journal mode**. Readers see the last committed state and never wait for the writer; the writer never waits for readers.
- Every connection gets a **busy timeout**, so the two writers (Link creation and the Click writer) wait briefly for SQLite's single write lock instead of failing at once with `SQLITE_BUSY`.
- The connection pool grows from 1 to a **small fixed size of 4**: enough for Redirect lookups to proceed while a batch is being written, small enough that write contention stays trivial.
- Both settings are applied on **every connection** through the `sqlite-jdbc` driver's connection properties (Spring datasource configuration), not by a one-off `PRAGMA` in a migration, so a new database, an existing one and a test database all behave the same.
- There is still **one data source** and one Link Store / Click Store boundary (ADR 0002).

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | Keep today's setup: rollback journal, one connection | No change; one file on disk; no new failure modes | Every Redirect lookup waits for the connection while a Click batch is written, so analytics slows the hot path. Violates story 22 and the intent of ADR 0012 | Rejected: it puts the Click writer directly in the Redirect's way |
| B | Keep the rollback journal; add a second, dedicated single-connection data source for the Click writer | No `-wal` / `-shm` files; the database file format is unchanged | In rollback mode, a committing writer takes an exclusive lock that blocks readers, so Redirect reads still wait behind batch commits (and get `SQLITE_BUSY` without a busy timeout anyway). Two data sources and two transaction managers to wire, test and reason about | Rejected: more moving parts, and it still doesn't meet story 22 |
| C | **WAL + busy timeout + a pool of 4, one data source** | Readers never wait for the writer, so Redirects stay fast during batch writes. Writers queue for the lock instead of failing. One data source, small configuration change. WAL is also generally faster for many small writes | WAL is a persistent property of the database file; going back needs a deliberate checkpoint and switch. Two extra files (`links.db-wal`, `links.db-shm`) sit beside `links.db` and must be backed up and moved with it. WAL needs shared memory, so the database can't live on a network file system. A write that waits longer than the busy timeout still fails | **Chosen** |
| D | Move to PostgreSQL now (R5) | Real concurrent readers and writers; the long-term production database | Every developer, test run and reviewer needs a database server before any load evidence asks for one. ADR 0002 defers this to R5, triggered by R13's load tests (ADR 0019) | Rejected for now: C removes the immediate contention at a fraction of the cost |

**In short:** WAL lets Redirect reads and Click writes stop blocking each other with one configuration change and one data source. We accept that the database becomes three files and that switching back is a deliberate act.

## Consequences

- **The data directory is three files, not one.** `links.db`, `links.db-wal` and `links.db-shm` belong together. Copying `links.db` alone while the app is running can lose recently committed Links and Clicks. Backups either stop the app first or use SQLite's online backup (`.backup` / `VACUUM INTO`). `docs/onboarding.md` and the runbook (R12) say so.
- **The maintainer's existing database** (`.local/`) is switched to WAL on its first start after this change. Release 1 builds can still open it, but reverting to a rollback journal requires `PRAGMA journal_mode=DELETE` with no other connection open.
- **Local disk only.** The data directory must be on a local file system (or a container volume backed by one), not NFS or SMB.
- **Writers still serialize.** SQLite has one write lock. Link creation and the Click writer take turns; the busy timeout bounds how long either waits. If Link creation ever fails with `SQLITE_BUSY`, the busy timeout or the Click batch size is too large, and R13/R14 measure it.
- **Do not shrink the pool back to 1 or turn WAL off** without a new ADR: that silently reintroduces the Redirect waiting behind Click batches.
- **Tests:** a concurrency integration test proves that a Redirect lookup completes while a Click batch holds the write lock, and that Link creation succeeds while the Click writer is busy (spec 0003, Testing Decisions). The whole existing suite runs with the new settings.
- **Scaling path unchanged.** When R13's evidence triggers it, the move to PostgreSQL (R5, ADR 0019) replaces these SQLite settings; the Store interfaces don't change.
