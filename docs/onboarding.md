# Onboarding

A guide for an engineer joining the project, or a reviewer who wants to run it and follow how it was built. Allow about 30 minutes. The [README](../README.md) is the map of every document. This page covers setting up, finding your way around, and making a change.

## 1. Read these first (10 minutes)

1. **[README](../README.md):** what the product does, the two planes (the product and the delivery orchestrator), and links to everything else.
2. **[`CONTEXT.md`](../CONTEXT.md):** the domain vocabulary. Code, tickets and docs all use these terms (*Link*, *Long URL*, *Short Code*, *Rule*, *Redirect*, *Lifetime*, *Expiry*, *Expired Link*, *Click*, *Click Recorder*, *Referrer Host*, *Agent Category*, *Device Class*), so learn them before reading code.
3. **[`docs/architecture.md`](architecture.md):** the two planes, the create and Redirect flows as sequence diagrams (with a Link's Lifetime and Expiry, and the `410` for an Expired Link), the Click flow, and SQLite's concurrency settings.
4. **[ADRs](adr/), at least 0001–0006:** why the service is built the way it is. Each one lists the options that were rejected.
5. **[`CLAUDE.md`](../CLAUDE.md):** the project charter, the working rules that people and Claude Code both follow.

## 2. Set up (10 minutes)

| Tool | Needed for | Install (macOS example) |
|---|---|---|
| **JDK 25** | Building and running the service | `brew install openjdk@25`, or Temurin 25 |
| Node.js 24 (as in CI) and Chrome | Browser checks and Lighthouse (`e2e/`) | `brew install node`, plus Google Chrome |
| `gh` (GitHub CLI) | Issues, PRs, the delivery board | `brew install gh && gh auth login` |
| Python 3.12+ and `uv` | The delivery orchestrator (from Release 2) | `brew install uv` |

Maven isn't needed: the Maven Wrapper (`./mvnw`) downloads it and every dependency.

```bash
git clone https://github.com/SanjuktaDavuluri/simple-url-shortener.git
cd simple-url-shortener
git config core.hooksPath .githooks    # refuses pushes to main locally (GitHub also protects main)
./mvnw verify                          # format check, compile, Error Prone, unit + integration tests
```

`./mvnw verify` is the single entry point, both locally and in CI. If it fails only on formatting, run `./mvnw spotless:apply`.

## 3. Run it

```bash
scripts/local.sh start        # build, then run in the background on http://localhost:8000 (data in .local/)
scripts/local.sh status       # also: stop | restart | logs | url
```

Open <http://localhost:8000> and shorten a link, or use the API:

```bash
curl -s -X POST localhost:8000/links -H 'content-type: application/json' -d '{"url": "https://example.com"}'
curl -si localhost:8000/<short_code>     # 302 to the Long URL
```

A Link can have an optional **Lifetime** of 1 to 365 whole days (`expires_in_days`; [spec 0004](specs/0004-expiring-links.md)). The `201` always includes `expires_at`. That is the Link's **Expiry** in ISO-8601 UTC (the moment of creation plus the Lifetime in exact 24-hour days), or `null` for a Link created without a Lifetime, which never expires:

```bash
curl -s -X POST localhost:8000/links -H 'content-type: application/json' \
     -d '{"url": "https://example.com/offer", "expires_in_days": 30}'
# 201 {"short_code":"Qp7tZ2w","short_url":"http://localhost:8000/Qp7tZ2w","long_url":"https://example.com/offer",
#      "manage_token":"<43 random characters, shown only this once>","expires_at":"2026-11-07T10:15:30.123Z"}

curl -si localhost:8000/Qp7tZ2w          # before expires_at: 302 to the Long URL, and one Click recorded
curl -si localhost:8000/Qp7tZ2w          # from expires_at on, the Expired Link answers:
# HTTP/1.1 410
# Content-Type: text/plain;charset=UTF-8
# Cache-Control: no-store
#
# This link has expired.
```

An Expired Link records no Click, and `HEAD` gets the same `410` without a body. An unknown Short Code is still `404`. An `expires_in_days` that isn't a whole number from 1 to 365 (`0`, `366`, `1.5`, `"30"`, `true`) gets a `422` with "expires_in_days must be a whole number of days from 1 to 365.", and no Link is created. The web page has the same option as an "Expires after (days)" field and shows the Expiry in UTC with the result. An Expired Link stays stored, and its Short Code is never reused ([ADR 0022](adr/0022-expired-links-kept-short-codes-never-reused.md)). There's nothing to configure.

**Try Stats locally ([spec 0005](specs/0005-click-stats-per-link.md)).** Use a scratch copy so your own service and data stay untouched:

```bash
PORT=8765 DATA_DIR=/tmp/shortener-scratch scripts/local.sh start
curl -s -X POST localhost:8765/links -H 'content-type: application/json' -d '{"url": "https://example.com"}'
# 201 {"short_code":"Ab3xK9q",...,"manage_token":"<shown only this once>"}
curl -si localhost:8765/Ab3xK9q                       # follow it a few times: each 302 records a Click
curl -s localhost:8765/links/Ab3xK9q/stats -H 'Authorization: Bearer <manage_token>'
```

The Stats show the Headline Click count (`clicks`, bots left out), `bot_clicks`, `last_click_at`, 30 days of `clicks_per_day`, and the Agent Category, Device Class and top Referrer Host breakdowns. Counts can trail the latest Redirects by about a second. A wrong or missing token, an unknown Short Code and a Link created before Stats all give the same `404`. In the browser, open <http://localhost:8765/stats>, enter the Short Code and the token (the token is never put in the URL). Stop the scratch copy with `PORT=8765 DATA_DIR=/tmp/shortener-scratch scripts/local.sh stop`.

To run a second, throwaway copy next to it, give it its own port and data directory: `PORT=8765 DATA_DIR=/tmp/shortener-scratch scripts/local.sh start`. The same pattern keeps automated runs away from your service.

Every environment variable, its default and meaning is in the [runbook's configuration reference](runbook.md#configuration-reference). It also covers starting, checking, diagnosing and stopping the service.

The `CLICK_*` settings are optional and bind to `shortener.clicks.*` in `application.properties` ([spec 0003](specs/0003-clickstream.md)). In tests, `IntegrationTest.storedClicks(shortCode)` flushes the Click Recorder and lists through the Click Store, so tests never sleep waiting for a batch.

**The database is three files (ADR 0021).** SQLite runs in WAL (write-ahead log) mode with a 5000 ms busy timeout and a pool of 4 connections, set on every connection in `application.properties`. Next to `links.db` you will find `links.db-wal` and `links.db-shm`. The three files belong together:

- **Backups:** don't copy `links.db` alone while the app is running, because recent Links can still be in `links.db-wal`. Either stop the app first and copy all three files, or take an online backup with SQLite: `sqlite3 links.db ".backup backup.db"` or `sqlite3 links.db "VACUUM INTO 'backup.db'"`.
- **Local disk only:** WAL needs shared memory, so the data directory must be on a local file system (or a container volume backed by one), never NFS or SMB.
- **Moving:** move or delete the three files together.
- **Existing databases** (a Release 1 `links.db`, the maintainer's `.local/`) switch to WAL on their first start. To go back to the rollback journal, stop the app so no other connection is open, then run `sqlite3 links.db "PRAGMA journal_mode=DELETE;"`. Don't do this while the service runs: the app turns WAL back on at its next start, and turning it off needs a new ADR.

Browser checks and Lighthouse, against a running app:

```bash
(cd e2e && npm ci && BASE_URL=http://localhost:8000 npm run all)
```

### Run it as a container

Needs Docker, nothing else (no JDK or Maven). Details: the runbook's [Run the container](runbook.md#run-the-container).

```bash
docker build -t shortener .
docker compose up                    # http://localhost:8000; data on the named volume
docker compose down                  # keeps the data; `down -v` deletes it
HOST_PORT=9000 HOST_MANAGEMENT_PORT=9001 BASE_URL=http://localhost:9000 PORT=9000 docker compose up   # beside your own service
```

CI's container job runs `scripts/container-test.sh`. Run it locally to reproduce CI: it builds the image, starts it on port 8765 with a scratch volume (never 8000 or `.local/`), and checks readiness, create and follow, persistence across a restart, non-root, a graceful `docker stop` and a misconfiguration exit. `PORT=... MANAGEMENT_PORT=... scripts/container-test.sh` changes the ports.

## 4. Find your way around the code

| Where | What |
|---|---|
| `LinkController`, `PageController` | The JSON API (`POST /links`, `GET /{shortCode}`) and the web page (`GET /`, `POST /`). `LinkController.followLink` is the Redirect: `404` for an unknown Short Code, `410 Gone` for an Expired Link, otherwise the `302`, after which, for a `GET`, it builds a Click and hands it to the Click Recorder (never for a `404`, a `410` or a `HEAD`). `createLink` reads `expires_in_days` without coercing it and always returns `expires_at` |
| `LinkService` | Create-a-Link: run the Rule Set, turn the optional Lifetime into the Expiry (from the injected `Clock`), draw Short Codes, retry on Collision |
| `Lifetime` | The Lifetime value type: a whole number of days from 1 to 365 (a constant in the type), its validation message, and the Expiry it gives from a creation instant. Shared by the JSON API and the web page; not a Rule ([spec 0004](specs/0004-expiring-links.md)) |
| `rules/` | One class per URL Rule, plus the ordered `RuleSet` ([ADR 0004](adr/0004-url-rules-as-separate-module.md)) |
| `LinkStore`, `JdbcLinkStore` | Storage interface and its SQLite implementation ([ADR 0002](adr/0002-sqlite-for-v1-storage.md)): save a Link with its optional Expiry, and `findDestination`, the Redirect's single primary-key lookup of the Long URL and Expiry. Nothing deletes Links, so an Expired Link's Short Code is never reused ([ADR 0022](adr/0022-expired-links-kept-short-codes-never-reused.md)) |
| `ShortCodeGenerator`, `RandomShortCodeGenerator` | 7-character base62 codes ([ADR 0003](adr/0003-random-short-codes.md)) |
| `clicks/Click`, `ClickClassification`, `AgentCategory`, `DeviceClass` | The Click value (Short Code, UTC time, Referrer Host, Agent Category, Device Class) and its attributes; nothing that could identify a person ([ADR 0013](adr/0013-clicks-store-minimal-non-personal-data.md), [spec 0003](specs/0003-clickstream.md)) |
| `clicks/ClickClassifier`, `BotPatterns` | Pure reduction of the raw `Referer` and `User-Agent` to the Referrer Host, Agent Category and Device Class; `BotPatterns` is the one maintained list of crawler and link-preview patterns |
| `clicks/ClickRecorder`, `QueuedClickRecorder`, `ClickRecorderStats` | The Click Recorder interface (`record` returns at once, never throws) and its implementation: a bounded queue, one background batch writer, dropped-and-counted loss, `stats()` and `flush()`, and a shutdown flush as a `SmartLifecycle` bean that stops after the web server ([ADR 0012](adr/0012-clicks-recorded-asynchronously.md)) |
| `clicks/ClickStore`, `JdbcClickStore` | The Click Store: save a batch, list a Short Code's Clicks oldest first. The only code that touches the `clicks` table; R2's stats API reads through it |
| `ClicksConfiguration` | Wires the `QueuedClickRecorder` from the `CLICK_*` settings and the UTC `Clock` that times each Click |
| `src/main/resources/application.properties` | Every setting with its default, including the Click settings and SQLite's WAL mode, busy timeout and pool of 4 ([ADR 0021](adr/0021-sqlite-wal-busy-timeout-and-small-connection-pool.md)) |
| `src/main/resources/db/migration/` | Flyway migrations; the schema only changes through a new migration (`V2__create_clicks.sql` adds `clicks`; `V3__add_link_expiry.sql` adds the nullable `links.expires_at`, `NULL` for every older Link, which never expires; `V4__add_manage_token_hash.sql` adds `links.manage_token_hash`) |
| `src/main/resources/templates/`, `static/` | Thymeleaf page and fragments, CSS, self-hosted HTMX ([ADR 0006](adr/0006-htmx-progressive-enhancement-web-page.md)) |
| `src/test/` | `*Test`: unit tests. `*IT`: integration tests that extend `IntegrationTest`, which flushes the Click Recorder and resets the database before every test, and controls Short Codes through a scripted generator and time through a fixed `TestClock`. Stored Clicks are observed through the Click Store (`storedClicks`). `TestApps` starts extra instances, for restarts and stand-ins |
| `docs/api/openapi.yaml` | The OpenAPI definition of the JSON API ([spec 0008](specs/0008-openapi-definition.md)). Generated from the controllers, never edited by hand: after changing the API run `scripts/openapi.sh`; `OpenApiDriftIT` fails `./mvnw verify` if the file is stale |
| `e2e/` | Playwright browser checks and Lighthouse (≥ 90 in every category) |
| `orchestrator/` | The delivery orchestrator (Python, uv): setup, commands and tests in [`orchestrator/README.md`](../orchestrator/README.md); design in [ADRs 0007–0011](adr/0007-delivery-orchestrator.md) and [0020](adr/0020-parallel-lanes-fan-out-in-the-graph-waits-at-the-join.md) |

## 5. How a change is made

Every change follows the same chain, so it can be traced afterwards:

```
roadmap item → spec (docs/specs/) → tickets (GitHub Issues) → branch → tests first → PR → CI → merge → board: Done
```

1. **Pick a ticket** from the [delivery board](https://github.com/users/SanjuktaDavuluri/projects/1). Each one links to its spec. Move it to *In Progress* with `scripts/board-status.sh <issue> "In Progress"`.
2. **Branch** from `main`: `feat/<issue>-<slug>`, or `fix/…`, `docs/…`, `chore/…`, `test/…` or `refactor/…`. There's one branch and one PR per ticket.
3. **Test first.** Write a failing test at the agreed seam, make it pass, repeat. Name things with the vocabulary in `CONTEXT.md`.
4. **Decisions:** if a change is hard to reverse, surprising without context, and the result of a real trade-off, propose an ADR. The maintainer must approve it before it's written. Smaller choices go in the spec or the PR.
5. **Open a PR** with `Closes #<issue>` and conventional-commit messages, then move the ticket to *In Review*. Both CI checks must pass: **Verify** and **Browser checks**. `main` accepts nothing except merged PRs.
6. **After merge,** move the ticket to *Done*. Update the [integration-testing plan's](plans/0001-integration-testing.md) matrix, and the spec's status when its last ticket ships.

Deferred ideas are never dropped silently: they go into [`docs/roadmap.md`](roadmap.md) with a reason.

## 6. House rules worth knowing early

- **Front end:** no inline scripts, inline styles or third-party assets. Everything is self-hosted ([ADR 0017](adr/0017-strict-content-security-policy-and-security-headers.md), from Release 3).
- **Privacy:** never store or log client IP addresses ([ADRs 0013](adr/0013-clicks-store-minimal-non-personal-data.md) and [0016](adr/0016-in-app-rate-limiting-per-client-ip.md)).
- **Secrets:** never in the repository, URLs or logs.
- **The maintainer's local service on :8000** may be in use. Run your own copy on another port and data directory instead of restarting it.

## 7. Repository setup, and how it came to be

- **Hosting.** [SanjuktaDavuluri/simple-url-shortener](https://github.com/SanjuktaDavuluri/simple-url-shortener) is public, with Issues, PRs, milestones per Release and the [delivery board](https://github.com/users/SanjuktaDavuluri/projects/1). The repository was created on 2026-10-07, before Release 1's implementation, so tickets exist before their code. The first commit is the design docs, made before any code.
- **Branch protection on `main`.**
  - Three required checks: **Verify**, **Browser checks** and **Orchestrator** (the last added with #26).
  - A PR is required, with 0 approvals: it's a solo project, and you can't approve your own PR.
  - Conversations must be resolved.
  - Admins are included.
  - Force-pushes and deletion are blocked.

  Renaming a CI job breaks its required check, so update the protection in the same PR.
- **The local guard.** A versioned `pre-push` hook in `.githooks/` refuses pushes to `main` before they reach GitHub. Enable it once per clone with `git config core.hooksPath .githooks`.
- **History.** Branch protection returned 403 while the repository was private on a free plan. The repository was rebuilt with a no-reply commit email, then made public so GitHub could enforce protection.

## 8. The delivery orchestrator (from Release 2)

The orchestrator automates the chain in section 5 under human control. It's a separate, development-time tool: it ends at a PR that's ready to merge, never deploys, and never connects to a running service. The design is in [ADRs 0007–0011](adr/0007-delivery-orchestrator.md) and [0020](adr/0020-parallel-lanes-fan-out-in-the-graph-waits-at-the-join.md); the full reference is [`orchestrator/README.md`](../orchestrator/README.md).

**Set up once.** Install [uv](https://docs.astral.sh/uv/), log in to GitHub (`gh auth login`), and have Claude access: `ANTHROPIC_API_KEY` in your environment, or a Claude login. Then `cd orchestrator && uv sync`.

**A Run, step by step:**

1. `orchestrate start <issue>`: starts Run `R-NNNN` for a GitHub Issue that names its roadmap item (for example `R10`).
2. Answer its clarifying questions in comments on the Issue, then `orchestrate resume <run>`. Every command returns as soon as the Run needs you; nothing keeps running.
3. Approve or reject what it submits: `orchestrate approve <run> spec`, `adr-NNNN`, `tickets`, `dependency:<lane>` and `amendment-N`, or `orchestrate reject <run> <checkpoint> --reason "…"`. A maintainer's `approved:<checkpoint>` label on the Issue also counts.
4. Review and **merge its PRs on GitHub** (it never merges): first the Run's documents, then one PR per ticket, as its Lanes run in parallel. Run `orchestrate resume <run>` after each merge.
5. When every Lane is merged or rolled back, release readiness checks traceability, and close-out opens a PR with the Run's report, Event Log and refreshed `delivery/metrics.md`. Merge it.

**Along the way:**

| Command | Use it to |
|---|---|
| `orchestrate status [run]` | see the Stages, what the Run waits for, and its cost |
| `orchestrate stop <run>` | Safe-stop it after the current step; `resume` continues |
| `orchestrate reject <run> lane:<key> --reason "…"` | roll back a paused Lane |
| `orchestrate waive <run> <pr> --reason "…"` | release readiness paused on a merged PR's commit without its ticket reference: record a waiver, then `resume` |
| `orchestrate replan <run>` | after changing an approved input on GitHub; only what depends on it is redone |
| `orchestrate verify <run>` / `--all` | check that Event Logs are intact |
| `orchestrate metrics` | regenerate `delivery/metrics.md` |

Before the first real Run on a new machine, try `scripts/orchestrator-smoke.sh` on a throwaway Issue.
