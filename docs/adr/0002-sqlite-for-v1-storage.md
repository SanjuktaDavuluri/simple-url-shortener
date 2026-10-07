---
status: accepted
date: 2026-10-07
---

# SQLite for v1 storage, with plain SQL via JdbcClient and Flyway migrations

Version 1 stores Links in **SQLite**, using the `sqlite-jdbc` driver. It needs no server and keeps development and tests free of extra infrastructure. All storage access goes through one **Link Store** interface, implemented with Spring's **`JdbcClient`** and plain SQL, so a later move to PostgreSQL (roadmap R5, reliability phase) is a contained change, not a rewrite. The schema is owned by **Flyway**: every schema change is a numbered, reviewed migration (`V1__create_links.sql`, …), applied the same way locally, in tests and in CI.

## Why plain SQL, not an ORM

This is deliberate. One table and two queries don't justify an ORM, and SQLite has no official Hibernate dialect. Plain SQL behind one interface keeps the storage boundary explicit and makes the PostgreSQL move a visible, testable change. **Do not "upgrade" this to JPA** without a new ADR.

## Options considered and the trade-offs

### Database

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **SQLite (file)** | No server; tests use a fresh real database per test; zero setup for reviewers | Single-writer and single-node; not the long-term production database | **Chosen for v1** |
| B | PostgreSQL in Docker from day one | Closer to production | Every developer and test run needs Docker before any load or concurrency justifies it | Deferred to R5, driven by load-test evidence (R13) |
| C | In-memory store | Simplest | Loses every Link on restart, so not credible even for v1 | Rejected |

### Data access

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Spring `JdbcClient` + plain SQL** | Thin and explicit; works cleanly with SQLite; the Link Store stays a small, obvious class | We write the SQL ourselves (two statements today) | **Chosen** |
| B | Spring Data JPA / Hibernate | Less hand-written SQL | Needs an unofficial SQLite dialect; hides behaviour behind the interface; heavy machinery for one table | Rejected |

### Schema management

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Flyway migrations** | Versioned, reviewable schema history; the same path everywhere; the PostgreSQL move becomes a visible migration | One more dependency | **Chosen** |
| B | `schema.sql` run at startup | Simplest | No version history, so schema evolution becomes invisible | Rejected |

**In short:** **SQLite over PostgreSQL** until evidence demands otherwise, **plain SQL over JPA** to keep the storage boundary explicit, and **Flyway over `schema.sql`** so every schema change is traceable.
