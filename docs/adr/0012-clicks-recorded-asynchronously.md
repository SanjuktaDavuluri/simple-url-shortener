---
status: accepted
date: 2026-10-07
---

# Clicks are recorded asynchronously, off the Redirect path, with bounded and measured loss

Every successful Redirect is a **Click** (CONTEXT.md). ADR 0005 chose `302` so that every Click passes through the shortener, and analytics (Click counts and a stats API) is built on that. The Redirect is also the product's **hot path**, and SQLite allows only one writer at a time (ADR 0002). Recording Clicks must never slow down or break a Redirect.

## Decision

- The Redirect handler hands a `Click` event to a **`ClickRecorder`** and returns the `302` immediately.
- The v1 `ClickRecorder` puts events on a **bounded in-memory queue**. A single background writer drains it and saves Clicks in **batches**.
- If the queue is full, the event is **dropped and counted**. The visitor is never blocked.
- On graceful shutdown, pending events are **flushed** before the app exits.
- The number of recorded, dropped and pending Clicks is exposed as **metrics** (with R12), so any loss is visible, not silent.
- Analytics read paths (Click counts, stats API) read only from stored Clicks. A Click becomes visible in analytics within a short, bounded delay.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | Synchronous insert before returning the `302` | Simplest; nothing lost | Every Redirect waits for a write and competes for SQLite's single writer; a failed write fails the Redirect, so analytics can break the core product | Rejected |
| B | **Asynchronous in-process queue, batched writes, drop-and-count when full, flush on shutdown** | Redirect latency unaffected; Redirects never fail because of analytics; batching cuts write contention | A crash or sustained overload loses a bounded, *measured* number of Clicks; counts lag by a short delay | **Chosen** |
| C | External event bus (Kafka, Redis Streams) | Durable and scalable | New infrastructure to run and secure, for one service | Rejected for now. The `ClickRecorder` interface keeps a later move contained |
| D | Write Clicks to the application log and process later | Very cheap | Analytics depends on log parsing; nothing is queryable until a batch job runs | Rejected |

**In short:** analytics is valuable, but the Redirect is the product. We trade a small, visible risk of losing Clicks for a Redirect path that analytics can never slow down or break.

## Consequences

- **Do not "fix" this into synchronous writes** without a new ADR. Measure first (load test R13).
- Click counts are *eventually* consistent. Tests wait for the writer to flush (or flush it explicitly) instead of asserting immediately after a Redirect.
- Queue size, batch size and flush interval are configuration, with conservative defaults.
- If the shortener later runs as several instances (R25), each instance has its own queue. The shared store, not the queue, is the source of truth.
