---
status: accepted
date: 2026-10-07
---

# SQLite for v1 storage, behind a single storage interface

Version 1 stores links in SQLite. It needs no server, is built into Python, and keeps development and tests free of extra infrastructure. All storage access goes through one interface, so we can move to PostgreSQL later as a contained change, not a rewrite. That move is expected in the reliability phase.

## Considered Options

- **PostgreSQL in Docker from day one.** It is closer to production, but every developer and every test run would need Docker before there is any load or concurrency to justify it. We rejected it for v1.
- **In-memory store.** It loses every link on restart, so it is not credible even for v1. We rejected it.
