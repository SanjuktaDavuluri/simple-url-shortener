# Runbook

How to run the shortener without the developers: start it, check it, diagnose it, stop it. Decisions behind it: [ADR 0015](adr/0015-observability-actuator-micrometer-structured-logs.md) (operability), [ADR 0012](adr/0012-clicks-recorded-asynchronously.md) (Clicks), [ADR 0021](adr/0021-sqlite-wal-busy-timeout-and-small-connection-pool.md) (SQLite). Spec: [0006](specs/0006-operability-basics.md). Terms are in the [glossary](../CONTEXT.md).

## Start

- **Jar:** `java -jar target/simple-url-shortener-*.jar` after `./mvnw package`. Every setting is an environment variable ([configuration reference](#configuration-reference)); nothing needs setting for a local run.
- **Script:** `scripts/local.sh start` (port 8000, management port 8081, data in `.local/`). It waits for Readiness before reporting success. A scratch copy: `PORT=8765 DATA_DIR=/tmp/shortener-scratch scripts/local.sh start`; its management port defaults to `PORT + 81`.
- **Startup stops, naming the variable,** when `BASE_URL` isn't an absolute `http`/`https` URL without a query or fragment, when a `CLICK_*` setting isn't positive, when `CLICK_BATCH_SIZE` exceeds `CLICK_QUEUE_CAPACITY`, or when `LOG_FORMAT` is neither `json` nor `text`. A failed Flyway migration also stops startup.
- One `INFO` startup line lists the effective settings (ports, Base URL, database path, Click settings, shutdown timeouts, log format). Use it to confirm what an instance runs with.

## Check

All on the management port (default 8081), never the public one:

| Check | Command | Healthy |
|---|---|---|
| Liveness | `curl -f http://localhost:8081/actuator/health/liveness` | `200`, `UP`. Depends on nothing but the process |
| Readiness | `curl -f http://localhost:8081/actuator/health/readiness` | `200`, `UP`, with components `readinessState`, `db`, `clickQueue` |
| Metrics | `curl http://localhost:8081/actuator/prometheus` | Prometheus text format ([metrics reference](#metrics-reference)) |

Use Liveness to decide on a restart and Readiness to decide on sending traffic. Readiness answers `503` when the database can't be reached, when the Click queue is full, and from the moment a shutdown begins (`OUT_OF_SERVICE`). `scripts/local.sh status` prints the Readiness status.

## Diagnose

**Find a request by Request ID.** Every response carries `X-Request-Id`. Logs are one JSON object per line, and every line written while handling that request has the same `request_id`. Given an ID from a user:

```bash
grep '"request_id":"<id>"' app.log      # or: jq 'select(.request_id == "<id>")' app.log
```

The access line has the method, the route (for example `/{short_code}`), the status and `duration_ms`. A caller's own `X-Request-Id` is used when it is 1–64 characters of letters, digits, `.`, `_` and `-`; otherwise a generated one replaces it. Background lines (the Click writer) have no Request ID.

| Symptom | Look at | Likely cause and action |
|---|---|---|
| Readiness `503`, `db` is `DOWN` | The `db` component; ERROR lines; the database file and its disk | Database file missing, unreadable or on a failed disk. Liveness stays `UP`, so don't restart for this alone. Fix the file or disk, then Readiness recovers |
| Readiness `503`, `clickQueue` is `OUT_OF_SERVICE` | `shortener_clicks_pending` against `shortener_clicks_queue_capacity` | The queue is completely full: Clicks arrive faster than the writer saves them, or the database is slow. Redirects still work, but new Clicks are dropped. Check the database, then consider a larger `CLICK_QUEUE_CAPACITY` or `CLICK_BATCH_SIZE` |
| Clicks dropped | `shortener_clicks_dropped_total` rising; WARN lines about dropped Clicks (at most one per `CLICK_FLUSH_INTERVAL`) | Same as above, or a failed batch save. Dropped Clicks are counted, never retried |
| Pool exhaustion, slow creates, `hikaricp_connections_pending` above 0 | `hikaricp_connections_active` at 4, `hikaricp_connections_acquire` latency | SQLite has a single writer, so writers queue on the write lock (busy timeout 5000 ms) while holding pooled connections. Look for long writes (a backup running `VACUUM INTO`, a slow disk). The pool size is fixed ([ADR 0021](adr/0021-sqlite-wal-busy-timeout-and-small-connection-pool.md)) |
| `5xx` responses | `http_server_requests_seconds_count{status=~"5.."}`; the ERROR line with the same `request_id` | The client sees no internals. The stack trace is in the log with the Request ID |
| Created Links have wrong Short URLs | The startup line's Base URL | `BASE_URL` is wrong for this deployment |
| Rejections spike | `shortener_rejections_total` by `rule` | A client is submitting disallowed Long URLs |
| Many Collisions | `shortener_collisions_total` | The Short Code space is filling up |

## Stop

Send `SIGTERM` (or `scripts/local.sh stop`, which waits for the whole shutdown). The order is fixed:

1. Readiness goes `OUT_OF_SERVICE`.
2. The public server refuses new connections; in-flight requests finish, for up to `SHUTDOWN_TIMEOUT`.
3. The Click Recorder flushes queued Clicks, for up to `CLICK_SHUTDOWN_TIMEOUT`; any left are dropped, counted and logged.
4. The database pool closes.

The log shows `Shutdown started` and `Shutdown complete`; the second carries the Clicks flushed and dropped.

**The bound:** `SHUTDOWN_TIMEOUT` + `CLICK_SHUTDOWN_TIMEOUT` + pool close, which is **about 20 s with the defaults** (10 s + 10 s, plus a few seconds). Set your process manager's or container's stop grace period **above** it (30 s recommended). Docker's default 10 s would cut the Click flush short.

## Configuration reference

Every setting is an environment variable with a default. A test fails the build when `application.properties` reads a variable that isn't in this table.

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8000` | Public HTTP port |
| `MANAGEMENT_PORT` | `8081` | Port for health and metrics. Keep it off the public network. `scripts/local.sh` defaults it to `PORT + 81` |
| `BASE_URL` | `http://localhost:8000` | The public address; every Short URL starts with it, and links to it are rejected as Self-links. Must be an absolute `http`/`https` URL with no query or fragment; a trailing `/` is removed |
| `DATABASE_PATH` | `links.db` | The SQLite database file; Flyway creates the schema on startup. See [data files](#data-files-and-backup) |
| `CLICK_QUEUE_CAPACITY` | `10000` | Most Clicks waiting in the Click Recorder's queue; a Click arriving when it is full is dropped and counted ([ADR 0012](adr/0012-clicks-recorded-asynchronously.md)) |
| `CLICK_BATCH_SIZE` | `500` | Most Clicks saved in one batch (one transaction). Must not exceed the capacity |
| `CLICK_FLUSH_INTERVAL` | `1s` | Longest the writer waits for a batch to fill; a Click is stored about this long after its Redirect |
| `CLICK_SHUTDOWN_TIMEOUT` | `10s` | Longest a shutdown waits for queued Clicks to be saved, after the web server stops |
| `SHUTDOWN_TIMEOUT` | `10s` | Longest a shutdown waits for in-flight requests to finish |
| `LOG_FORMAT` | `json` | Console format: `json` (one ECS JSON object per line, with `request_id`) or `text`. Anything else stops startup |

Log levels use Spring's standard binding: `LOGGING_LEVEL_ROOT=WARN`, or `LOGGING_LEVEL_<LOGGER_NAME>` with dots as underscores, for example `LOGGING_LEVEL_IO_GITHUB_SANJUKTADAVULURI_SHORTENER=DEBUG`.

**Fixed, not configurable** ([ADR 0021](adr/0021-sqlite-wal-busy-timeout-and-small-connection-pool.md); changing them needs a new ADR): connection pool size 4, WAL journal mode, busy timeout 5000 ms.

## Metrics reference

On `/actuator/prometheus`. Counters get `_total` and timers `_seconds_*` in Prometheus. No tag ever carries a Short Code, a Long URL or a host.

| Name | Type | Tags | Meaning |
|---|---|---|---|
| `http.server.requests` | timer | `method`, `uri` (route pattern), `status`, `outcome` | Public requests per route: rate, errors, latency |
| `shortener.links.created` | counter | none | Links created |
| `shortener.redirects` | counter | `outcome` = `found` / `not_found` | `GET` Redirects by outcome |
| `shortener.rejections` | counter | `rule` (for example `self_link`, `http_scheme`) | Long URLs refused, by the Rule that refused them |
| `shortener.collisions` | counter | none | Short Codes drawn again because they were taken |
| `shortener.clicks.recorded` | function counter | none | Clicks saved |
| `shortener.clicks.dropped` | function counter | none | Clicks dropped (queue full, failed batch, or unflushed at shutdown) |
| `shortener.clicks.pending` | gauge | none | Clicks queued now (queue depth) |
| `shortener.clicks.queue.capacity` | gauge | none | `CLICK_QUEUE_CAPACITY` |
| `jvm.*`, `process.*`, `system.*` | standard | varies | Memory, GC, threads, CPU |
| `hikaricp.*` | standard | `pool` | Connection pool: active, pending, acquire time |

**Queries worth alerting on** (PromQL; thresholds are starting points):

- Click loss: `increase(shortener_clicks_dropped_total[5m]) > 0`
- Queue nearly full: `shortener_clicks_pending / shortener_clicks_queue_capacity > 0.8`
- Server errors: `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) > 0`
- Redirect latency: `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/{short_code}"}[5m]))) > 0.1` (needs histogram buckets enabled)
- Pool pressure: `hikaricp_connections_pending > 0` for several minutes
- Not ready: the Readiness check failing, probed from outside

## Data files and backup

The database is three files that belong together, next to each other at `DATABASE_PATH`: `links.db`, `links.db-wal` (recent writes not yet merged) and `links.db-shm` (shared memory index). Keep them on a local file system, never NFS or SMB.

- **Safe backup while running:** `sqlite3 links.db ".backup backup.db"` (or `VACUUM INTO 'backup.db'`). The result is one consistent file.
- **Cold backup:** stop the service, then copy all three files.
- **Never** copy `links.db` alone while the service runs: recent Links can still be in `links.db-wal`.
- Move or delete the three together. To go back from WAL, stop the service first; the service turns WAL back on at its next start.

## Logging rules

- **Contain:** timestamp, level, logger, message, exception stack traces, `request_id`, and one access line per public request (`http_method`, `route`, `status`, `duration_ms`).
- **Never contain:** Long URLs, `Referer` or `User-Agent` values, IP addresses, request bodies, query strings, `Authorization` headers or Manage Tokens ([ADR 0013](adr/0013-clicks-store-minimal-non-personal-data.md), [ADR 0014](adr/0014-creator-only-stats-via-manage-token.md)). Tests enforce this.
- **Volume:** about 300 bytes per access line, so about 5 GB/day at 200 Redirects/s. To turn the access line off, raise its logger to `WARN` (`LOGGING_LEVEL_IO_GITHUB_SANJUKTADAVULURI_SHORTENER_REQUESTIDFILTER=WARN`, or the whole base package).
- **Rotation** is the platform's job (container log driver, `journald`, `logrotate`). The service only writes to the console; `scripts/local.sh` redirects it to a file under `.local/`.

## Exposure rules

- Serve the public port only behind **HTTPS** (a reverse proxy or load balancer); the service speaks plain HTTP.
- Keep the **management port on the operator network only**: bind or publish it to the host or the private network, never through the public ingress. It has no authentication ([ADR 0015](adr/0015-observability-actuator-micrometer-structured-logs.md)). Only `health` and `prometheus` are exposed, and every Actuator path is `404` on the public port.
