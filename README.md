# Simple URL Shortener

A small, backend-focused URL shortener, built in the open as a **complete SDLC case study**. The system is deliberately simple. The point is the *process*: every requirement, decision, change and incident is traceable from idea to production, through three phases:

1. **Greenfield**: from idea to a tested v1.
2. **Brownfield**: evolving the running system feature by feature.
3. **Reliability**: making it production-grade, with measurements.

> **Status:** **Release 2 complete**, tagged [`v2.0.0`](docs/releases/v2.0.0.md), after **Release 1** (greenfield), tagged [`v1.0.0`](docs/releases/v1.0.0.md). Release 2 delivered the [delivery orchestrator](#the-delivery-orchestrator) (R18, [spec 0002](docs/specs/0002-delivery-orchestrator.md)) and then used it to deliver the Clickstream (R10), Expiring Links (R3), Click stats per Link (R2), operability (R12), the container image (R11) and the OpenAPI definition (R21). Each was an orchestrator Run with its spec, ADRs, tickets, PRs and Event Log committed ([delivery metrics](delivery/metrics.md)). **Release 3** (hardening and reliability evidence) is designed (ADRs 0016–0019) and is next. **Track the work:** [delivery board](https://github.com/users/SanjuktaDavuluri/projects/1) · [milestones](https://github.com/SanjuktaDavuluri/simple-url-shortener/milestones).

## Reviewer's guide: start here

This README is the map. Every question a reviewer or a new engineer usually asks links to the document that answers it.

| If you want to… | Read |
|---|---|
| Read everything in one document: architecture, three scenarios, testing, risks, limitations | [Engineering summary](docs/summary.md) |
| See the system in one picture | [Two planes: the product and how it is delivered](#two-planes-the-product-and-how-it-is-delivered) (below) |
| Know what the product does today | [The product](#the-product) · [Spec 0001: v1 core](docs/specs/0001-v1-core.md) · [Spec 0003: Clickstream](docs/specs/0003-clickstream.md) · [Spec 0004: Expiring Links](docs/specs/0004-expiring-links.md) · [Spec 0005: Click stats per Link](docs/specs/0005-click-stats-per-link.md) |
| Learn the vocabulary (Link, Short Code, Rule, Redirect, Lifetime, Expiry, Expired Link, Click, Click Recorder, Referrer Host, Agent Category, Device Class) | [`CONTEXT.md`](CONTEXT.md) |
| Follow a request through the code | [`docs/architecture.md`](docs/architecture.md): sequence diagrams for create (with the Lifetime and Expiry) and the Redirect (`404`, `302` + Click, `410` for an Expired Link), the Click flow, and SQLite's WAL and pool settings ([ADR 0021](docs/adr/0021-sqlite-wal-busy-timeout-and-small-connection-pool.md)) |
| Understand *why* it is built this way | [Decisions (ADRs)](#decisions) below · [`docs/adr/`](docs/adr/) |
| Understand the delivery orchestrator | [The delivery orchestrator](#the-delivery-orchestrator) below · [`orchestrator/README.md`](orchestrator/README.md) · ADRs [0007](docs/adr/0007-delivery-orchestrator.md)–[0011](docs/adr/0011-orchestrator-replanning-and-lineage.md) |
| See what each Release delivered | [Release notes](docs/releases/) · [`CHANGELOG.md`](CHANGELOG.md) |
| See what's planned, deferred, and why | [`docs/roadmap.md`](docs/roadmap.md): releases, ordering and a re-prioritisation log |
| Trace a feature from requirement to code | [Specs](docs/specs/) → [Issues](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues?q=is%3Aissue) → [pull requests](https://github.com/SanjuktaDavuluri/simple-url-shortener/pulls?q=is%3Apr) → commits. Each PR says `Closes #n` |
| See how it is tested | [Integration-testing plan](docs/plans/0001-integration-testing.md) (traceability matrix, [spec 0003 coverage](docs/plans/0001-integration-testing.md#spec-0003-coverage), [spec 0004 coverage](docs/plans/0001-integration-testing.md#spec-0004-coverage)) · `src/test/` · [browser checks](e2e/README.md) · [CI workflow](.github/workflows/ci.yml) |
| Read the API contract | [`docs/api/openapi.yaml`](docs/api/openapi.yaml): OpenAPI 3 definition of the JSON API ([spec 0008](docs/specs/0008-openapi-definition.md)), generated from the code and checked for drift in `./mvnw verify`. Regenerate with `scripts/openapi.sh` |
| Run it or contribute | [Quick start](#quick-start) below · [onboarding guide](docs/onboarding.md) |
| Operate it | [Runbook](docs/runbook.md): start, check, diagnose, stop, configuration and metrics references |
| See the working rules the team (and Claude Code) follow | [`CLAUDE.md`](CLAUDE.md): project charter |

## Two planes: the product and how it is delivered

The repository holds two separate things, with **separate entry points**:

- **The product plane** is the URL shortener: a Java service, always on, used by end users. It's started with `java -jar`, `scripts/local.sh` or a container.
- **The delivery plane** is the **delivery orchestrator**: a development-time tool in `orchestrator/` (Python, built in Release 2), run on demand by an engineer with `orchestrate`.

The orchestrator turns a request into specs, tickets, code and pull requests. **It never deploys, and it never connects to a running service.** Its test stages start their own temporary copy of the service. The only way a change reaches the product is a pull request that **a human reviews and merges** ([ADR 0007](docs/adr/0007-delivery-orchestrator.md)).

```mermaid
flowchart LR
    subgraph DEV["Delivery plane: development time (orchestrator/, Python), on demand"]
        direction TB
        CLI["Engineer runs<br/>orchestrate start (issue number)"] --> ORCH["Orchestrator<br/>LangGraph stages and approvals<br/>Agent SDK steps"]
        ORCH --> WT["Git worktree<br/>code, tests, docs"]
        WT --> TMP["Temporary service instance<br/>own port, temporary data dir<br/>mvn verify, e2e, load smoke test"]
    end

    ORCH <--> LLM["Claude API"]
    ORCH -->|"branches, PRs, Issue comments, run log"| GH["GitHub<br/>Issues, PRs, CI, delivery board"]
    GH -->|"a human reviews and merges"| MAIN["main"]

    subgraph RUN["Product plane: runtime (Java service), always on"]
        SVC["Shortener service<br/>java -jar, scripts/local.sh, container"]
    end

    MAIN -->|"build and deploy: a separate step, not the orchestrator"| SVC
    USERS(["End users"]) --> SVC
    ORCH -.-x|"never connects"| SVC
```

## The product

<p>
  <img src="docs/images/web-page-light-desktop.png" alt="The web page in light mode on a desktop, showing a newly created Short URL with a Copy button" width="560">
  <img src="docs/images/web-page-dark-phone-rejected.png" alt="The web page in dark mode on a phone, showing the Rejection Reason under the Long URL field" width="200">
</p>

### Available now (v1, [spec 0001](docs/specs/0001-v1-core.md))

- **Shorten:** submit a Long URL and get back a Short URL such as `http://localhost:8000/Ab3xK9q`, through the JSON API (`POST /links`) or the web page.
- **Redirect:** opening a Short URL sends you to its Long URL with a `302 Found`.
- **URL Rules:** only `http`/`https` addresses with a host, at most 2048 characters, and never a link back to the shortener itself. Each refusal comes with a clear Rejection Reason (`422`).
- **Web page:** a single server-rendered page with no full page reloads, copy-to-clipboard, and light and dark mode. It works with JavaScript off.

### Added in Release 2 (`v2.0.0`) (roadmap R10, [spec 0003](docs/specs/0003-clickstream.md))

- **Clicks recorded:** every successful `GET` Redirect records one Click (Short Code, time, Referrer Host, Agent Category, Device Class) in a `clicks` table. Clicks are queued and saved in batches by a background writer, so the Redirect never waits for the database. The `302` is sent before the Click is handed over, and a failure while handing it over costs only that Click (logged as a warning), never the Redirect; a Redirect also completes while a Click batch holds SQLite's write lock. Loss is bounded and counted, never an error ([ADR 0012](docs/adr/0012-clicks-recorded-asynchronously.md)): a full queue drops new Clicks, and a batch that fails to save is dropped whole, with no retry. Drops are logged as a single warning carrying only the count, at most once per flush interval (any held back are logged when the writer stops), and the writer logs its start and stop. On a normal shutdown the writer stops after the web server, so Redirects served while shutting down are still recorded, and it saves the queued Clicks within `CLICK_SHUTDOWN_TIMEOUT`; any it cannot save in time are dropped and counted. The full `Referer` and the raw `User-Agent` are never stored ([ADR 0013](docs/adr/0013-clicks-store-minimal-non-personal-data.md)). A `404`, a `HEAD` request and Link creation record nothing. Single Clicks are never readable over HTTP; a Link's creator reads their counts as its Stats (R2, below).

How it works: the [Redirect and Click flows](docs/architecture.md#the-click-flow-clickstream) and [SQLite concurrency](docs/architecture.md#sqlite-concurrency-adr-0021) in the architecture, the [glossary](CONTEXT.md) terms *Click Recorder*, *Referrer Host*, *Agent Category* and *Device Class*, the settings and code tour in [onboarding](docs/onboarding.md), the test evidence in [plan 0001](docs/plans/0001-integration-testing.md#spec-0003-coverage), and the [roadmap](docs/roadmap.md) (R10: done, with its PRs).

### Added in Release 2 (`v2.0.0`): Click stats per Link (roadmap R2, [spec 0005](docs/specs/0005-click-stats-per-link.md))

- **Manage Token on create (#104):** `POST /links` now also returns a `manage_token` next to `short_code`, `short_url` and `long_url`, sent with `Cache-Control: no-store`. It is shown this once and never again: the shortener stores only its SHA-256 hash, never the token itself, and never logs it ([ADR 0014](docs/adr/0014-creator-only-stats-via-manage-token.md), [ADR 0023](docs/adr/0023-manage-token-hashed-with-sha-256.md)). Every other field, status code and error is unchanged; a rejected or failed create carries no token. Links created before this change have no Manage Token and keep Redirecting as before. The token authorises reading the Link's Stats (#105, below); the web page doesn't show it yet. Glossary term *Manage Token* in [CONTEXT.md](CONTEXT.md); test evidence in [plan 0001](docs/plans/0001-integration-testing.md#spec-0005-coverage).
- **A Link's headline Stats over the JSON API (#105):** `GET /links/{short_code}/stats` with `Authorization: Bearer <manage_token>` (the scheme in any case) returns `200` JSON: the Link's `short_code`, `short_url`, `long_url` and `created_at`; `generated_at`; the Headline Click count `clicks` (non-bot Clicks, Agent Category `browser` plus `other`); `bot_clicks`; `last_click_at` (the latest non-bot Click, or `null`); and `by_agent_category` with `browser`, `other` and `bot` always present. Times are ISO-8601 UTC to the millisecond. A Link with no Clicks gets zeros, not an error, and an Expired Link keeps its Stats. Stats are counts only, never single Clicks or raw `Referer` and `User-Agent` values, and can trail the latest Redirects by about one flush of the Click Recorder. Every failure (no or malformed `Authorization` header, an empty or wrong token, another Link's token, an unknown Short Code, or a Link created before #104, which has no Manage Token) gets the same `404` `application/problem+json`, byte for byte, so the endpoint never confirms that a Short Code exists ([ADR 0014](docs/adr/0014-creator-only-stats-via-manage-token.md)). The token is always hashed and compared in constant time, even for an unknown Short Code, and neither it nor its hash is ever logged ([ADR 0023](docs/adr/0023-manage-token-hashed-with-sha-256.md)). Every Stats answer is `Cache-Control: no-store`. The breakdowns came with #107 (below); the web page came with the Stats page ticket. Glossary terms *Stats* and *Headline Click count* in [CONTEXT.md](CONTEXT.md); test evidence in [plan 0001](docs/plans/0001-integration-testing.md#spec-0005-coverage).
- **Manage Token on the web page ([#106](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/106)):** when the web page creates a Link, with or without a Lifetime, the result shows its Manage Token once, under the Short URL, in a read-only "Manage Token" field with its own Copy button (it announces "Manage Token copied to the clipboard.") and the note "Keep this safe: it's the only way to see this Link's stats, and it won't be shown again." The field has no `name`, so the token is never submitted back and never goes into a URL. Every answer to the form post (`POST /`), full page or HTMX fragment, is sent with `Cache-Control: no-store`. The home page, a rejected Long URL, an invalid Lifetime and a `503` show no token. It works the same with and without JavaScript (the Copy button appears only with JavaScript), with no inline script or style.
- **Stats breakdowns (#107):** the Stats JSON also carries `clicks_per_day`, exactly 30 `{"date","clicks"}` entries for the 30 UTC days ending today (dates `yyyy-MM-dd`, oldest first, a day without Clicks at 0); `by_device_class` with `desktop` and `mobile` always present; `top_referrer_hosts`, at most 10 `{"host","clicks"}` entries, most Clicks first and ties by host; and `no_referrer_host`, the Clicks that had no Referrer Host. All four count non-bot Clicks only, so `by_device_class` sums to `clicks` and `top_referrer_hosts` plus `no_referrer_host` account for every non-bot Click when there are 10 Referrer Hosts or fewer; bots appear only in `bot_clicks` and `by_agent_category`. Days are UTC days whatever the server's time zone. A Click older than the 30-day window leaves `clicks_per_day` but still counts in `clicks` and the other breakdowns. A Link with no Clicks gets 30 zero days, both Device Classes at 0, an empty `top_referrer_hosts` and `no_referrer_host` 0. Only Referrer Hosts are shown, never a raw `Referer` URL or `User-Agent` ([ADR 0013](docs/adr/0013-clicks-store-minimal-non-personal-data.md)). Test evidence in [plan 0001](docs/plans/0001-integration-testing.md#spec-0005-coverage).

- **Stats web page (#112, #160, #161):** `/stats` is a form for the Short Code (or Short URL) and the Manage Token, linked from the create result ("See its stats"). `POST /stats` shows the Headline Click count, bot Clicks, the last Click, the 30 UTC days as an accessible table with CSS-only bars, the Device Class split and the top 10 Referrer Hosts, with a note that counts can lag by about one flush interval. Every failure shows one `404` message that never reveals whether the Short Code exists. The token field is a password field and never goes into a URL. It works with and without JavaScript, every answer is `Cache-Control: no-store`, and Lighthouse stays at or above 90. Tests prove that no token, token hash, `Bearer` value, raw `Referer` or `User-Agent` reaches a log or a response, and that a token in the query string is ignored.

### Added in Release 2 (`v2.0.0`): Operability basics (roadmap R12, [spec 0006](docs/specs/0006-operability-basics.md), [ADR 0015](docs/adr/0015-observability-actuator-micrometer-structured-logs.md))

- **Request ID on every response (#117):** every response from the public port, including the `302` Redirect, Rejections, `404`s, the web page, static assets and a `500`, now carries an `X-Request-Id` header. A caller's own `X-Request-Id` of 1 to 64 characters from `A-Z`, `a-z`, `0-9`, `.`, `_` and `-` is sent back unchanged. Any other value, or no header, is replaced by a new random UUID, and the rejected value is never logged. While the request is handled, the ID is in the logging MDC as `request_id`, so the structured logs that come later in spec 0006 can show it on every line. It is removed afterwards, even when the request fails. An unexpected error answers `500` with no exception message and no stack trace (`server.error.include-stacktrace=never` and `include-message=never` are now set explicitly). Every other status, body, route and header is unchanged. Test evidence is in [plan 0001](docs/plans/0001-integration-testing.md#5-traceability-matrix).

- **Liveness and Readiness on a separate management port (#116):** `MANAGEMENT_PORT` (default `8081`) serves only `/actuator/health` (Liveness, Readiness with `db`, `clickQueue` and `readinessState`) and `/actuator/prometheus`; the public port has no Actuator path. Readiness is `503` while the database is unreachable or the Click queue is full.
- **Configuration is validated at startup (#118):** a relative or malformed `BASE_URL` (not `http`/`https`, or with a query or fragment), a non-positive Click setting, a batch size above the queue capacity or an unknown `LOG_FORMAT` stops startup with a message naming the variable and its value.
- **Structured JSON logs (#119):** each log line is one JSON object with the `request_id`, and each request logs exactly one access line (method, route pattern, status, duration). `LOG_FORMAT=text` gives plain text for local debugging. Long URLs, referrers, user agents, authorization headers, query strings, IP addresses and Manage Tokens never reach a log.
- **Metrics (#120):** `/actuator/prometheus` carries domain counters (`shortener_links_total`, `shortener_redirects_total{outcome}`, `shortener_rejections_total{rule}`, `shortener_collisions_total`), Click gauges and counters (`shortener_clicks_recorded_total`, `_dropped_total`, `_pending`, `_queue_capacity`) and the standard HTTP, JVM and connection-pool metrics. No Short Code, Long URL or Rejection Reason is ever a tag.
- **Graceful shutdown (#121):** Readiness goes `OUT_OF_SERVICE`, the web server stops accepting connections, in-flight requests finish (`SHUTDOWN_TIMEOUT`), queued Clicks are flushed (`CLICK_SHUTDOWN_TIMEOUT`), then the pool closes. One line announces the start of the shutdown and one summarises the Clicks saved and dropped; one startup line lists the effective settings.
- **Local and CI use Readiness (#122):** `scripts/local.sh start` waits for Readiness and `status` shows it; `stop` waits for the graceful shutdown; the CI browser-check job polls Readiness. The job names are unchanged, so the required checks are too.
- **First runbook (#123):** [`docs/runbook.md`](docs/runbook.md) covers start, check, diagnose (by Request ID), stop, data and backup, and a configuration reference. A test fails the build if a setting in `application.properties` is missing from that reference.

### Added in Release 2 (`v2.0.0`): Expiring Links (roadmap R3, [spec 0004](docs/specs/0004-expiring-links.md), [ADR 0022](docs/adr/0022-expired-links-kept-short-codes-never-reused.md))

Delivered early, ahead of Release 3, by orchestrator Run R-0002 from Issue [#83](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/83). Its one-line request ("Links should be able to expire") was clarified on the Issue before the spec was written (R23). Tickets #97–#102.

- **Expiring Links, through the API ([#97](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/97)):** `POST /links` takes an optional `expires_in_days`, a Lifetime of 1 to 365 whole days. The `201` always includes `expires_at`: the Expiry in ISO-8601 UTC (creation time plus the Lifetime in exact 24-hour days), or `null` for a Link created without a Lifetime, which never expires, as every existing Link does. From the exact instant of its Expiry, the Short URL answers `410 Gone` (`text/plain`, `Cache-Control: no-store`, body `This link has expired.`, no body for `HEAD`) instead of Redirecting, and records no Click. The Expired Link stays stored and its Short Code is never reused. An unknown Short Code is still `404`.
- **Invalid Lifetimes refused ([#98](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/98)):** an `expires_in_days` that is present and not `null` must be a JSON whole number from 1 to 365. Anything else (`0`, `366`, `1.5`, `"30"`, `true`, an object) is never coerced: it gets a `422` problem detail "expires_in_days must be a whole number of days from 1 to 365." and creates no Link. A request is checked in order: a `url` that is missing, `null` or not a JSON string is a malformed request (`422`, as before), then the Lifetime, then the URL Rules, so a Rule-breaking `url` with a valid or no Lifetime keeps its Rejection Reason.
- **Expiring Links on the web page ([#100](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/100)):** the form has an optional "Expires after (days)" field, with the hint "Leave blank to keep the link forever". Left blank, the Link never expires. With a Lifetime, the result shows the Expiry in UTC to the minute under the Short URL (for example "Expires on 2026-11-07 10:15 UTC"). The server is the only judge of the value, as for the Long URL: anything that isn't a whole number from 1 to 365 (`0`, `366`, `1.5`, `abc`) gets a `422` with "expires_in_days must be a whole number of days from 1 to 365." under the field, keeps what was typed in both fields, and creates no Link. As in the API, the Lifetime is checked before the URL Rules. It works the same with and without JavaScript (one template, [ADR 0006](docs/adr/0006-htmx-progressive-enhancement-web-page.md)), with no new JavaScript.
- **Expired Links are kept, and their Short Codes are never reused ([#99](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/99), [ADR 0022](docs/adr/0022-expired-links-kept-short-codes-never-reused.md)):** nothing deletes or purges a Link, so drawing an Expired Link's Short Code again is a Collision, and its Short URL keeps answering `410` and never sends anyone somewhere new. Its Clicks from before the Expiry are kept for auditing and R2. The Expiry survives a restart. `V3__add_link_expiry.sql` adds one nullable `expires_at` column, so every Link created before it never expires and keeps its Clicks. There is no background job and no new setting.
- **Browser checks ([#101](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/101)):** creating an expiring Link and an invalid Lifetime are checked in Chrome with JavaScript on and off, and Lighthouse stays at or above 90 in every category.

How it works: the [create and Redirect flows](docs/architecture.md#request-flows) and [a Link's Expiry](docs/architecture.md#a-links-expiry-expiring-links) in the architecture, the [glossary](CONTEXT.md) terms *Lifetime*, *Expiry* and *Expired Link*, the API example and code tour in [onboarding](docs/onboarding.md#3-run-it), the test evidence in [plan 0001](docs/plans/0001-integration-testing.md#spec-0004-coverage), and the [roadmap](docs/roadmap.md) (R3: done, with its PRs).

### Added in Release 2 (`v2.0.0`): OpenAPI definition (roadmap R21, [spec 0008](docs/specs/0008-openapi-definition.md))

The JSON API's contract is in [`docs/api/openapi.yaml`](docs/api/openapi.yaml): create a Link, the Redirect and the Stats endpoint (Bearer Manage Token). It is generated from the code, never edited by hand. After changing the API, regenerate it with `scripts/openapi.sh`; the drift test fails `./mvnw verify` if the file is stale. Test evidence: [plan 0001](docs/plans/0001-integration-testing.md#spec-0008-coverage-openapi-tickets-184).

### Added in Release 2 (`v2.0.0`): Container image (roadmap R11, [spec 0007](docs/specs/0007-dockerize.md))

- **One image, no local toolchain (#172):** `docker build -t shortener .` builds a multi-stage image (Maven build stage, JRE 25 runtime stage) holding only the JRE and the jar. The process runs as the unprivileged user `shortener` (UID 10001), the SQLite database lives on the `/data` volume, and a `HEALTHCHECK` polls Readiness on the management port. `docker stop` sends `SIGTERM` to the Java process (PID 1), so the graceful shutdown runs and the container exits `0` within the documented bound. A misconfigured container exits non-zero and names the variable.
- **Compose for a local run (#173):** `docker compose up` runs the service on port `9000` with `BASE_URL` to match; the management port is published to the host's loopback only; the settings pass through from the environment with the runbook's defaults. A drift test fails the build if `compose.yaml` and the runbook's settings differ.
- **Built and tested in CI (#174):** a "Container (image build + container tests)" job builds the image on every pull request and runs `scripts/container-test.sh`: the container becomes healthy, serves a Link, keeps it across a restart on the same volume, runs as non-root and stops gracefully. The image is never pushed to a registry. The job is additional to the three required checks.
- **Documented (#175):** the container section of the [runbook](docs/runbook.md), the [onboarding guide](docs/onboarding.md#run-it-as-a-container) and the [architecture](docs/architecture.md). Test evidence: [plan 0001](docs/plans/0001-integration-testing.md), phase P5.

### Planned ([roadmap](docs/roadmap.md))

| Release | Feature | Decided in |
|---|---|---|
| 3 | Hardening: rate limits, strict security headers, blocking private and internal addresses | ADRs [0016](docs/adr/0016-in-app-rate-limiting-per-client-ip.md), [0017](docs/adr/0017-strict-content-security-policy-and-security-headers.md), [0018](docs/adr/0018-private-address-rule-without-dns.md) |
| 3 | Reliability evidence: load-test baseline against SLOs, failure-mode testing, scaling path | [ADR 0019](docs/adr/0019-staged-evidence-triggered-scaling-path.md) · roadmap R13, R14 |
| 3 | Requirement-clarification case study write-up (R23); the feature it clarified, Expiring Links (R3), is already done above | roadmap R23, R24 |

## The delivery orchestrator

*Built in Release 2 (roadmap R18, tickets #26–#35; spec 0002 implemented), with the Claude Agent SDK as its agent. Available now: a full Run with parallel Lanes and Re-plan, under policy guardrails, with retries, pause and resume, Rollback, Safe-stop and cost caps: intake, requirements, design, decompose, Lanes (implement → document → PR → human merge), release readiness and close-out, via `start`, `status`, `resume`, `replan`, `approve`, `reject`, `waive`, `verify` and `metrics`. See [`orchestrator/README.md`](orchestrator/README.md).*

An engineer starts a run from a GitHub Issue: `orchestrate start <issue>`. The orchestrator then drives the request through fixed stages: **intake → requirements → design → decompose → per-ticket lanes (implement → document → PR) → release readiness → close-out**. Each stage has an exit gate. A human approves the spec, any ADRs, the tickets and every merge. Each run leaves a tamper-evident event log and a report in the repository, and delivery metrics are derived from them.

| Topic | ADR |
|---|---|
| What it is, where it lives, how it is used, what it's built with (Python, Claude Agent SDK, LangGraph) | [0007](docs/adr/0007-delivery-orchestrator.md) |
| Stages, gates, human approvals, retries, rollback and safe-stop | [0008](docs/adr/0008-orchestrator-stage-graph-and-governance.md) |
| State, the hash-chained audit trail and delivery metrics | [0009](docs/adr/0009-orchestrator-state-audit-and-metrics.md) |
| Policy guardrails, checked before every action and after every step | [0010](docs/adr/0010-orchestrator-policy-guardrails.md) |
| Re-planning when inputs change, without redoing unaffected work | [0011](docs/adr/0011-orchestrator-replanning-and-lineage.md) |
| Parallel Lanes: agent work fans out in the graph, human waits are held at the join | [0020](docs/adr/0020-parallel-lanes-fan-out-in-the-graph-waits-at-the-join.md) |

## Decisions

Every significant decision is an ADR that lists the options weighed and why one won.

| # | Decision | Area |
|---|---|---|
| [0001](docs/adr/0001-java-spring-boot-stack.md) | Java 25 (LTS), Spring Boot 4.1, Maven Wrapper | Stack |
| [0002](docs/adr/0002-sqlite-for-v1-storage.md) | SQLite via `JdbcClient` and Flyway, behind a Link Store interface | Storage |
| [0003](docs/adr/0003-random-short-codes.md) | 7 random base62 characters, independent of the Long URL, retried on Collision | Short Codes |
| [0004](docs/adr/0004-url-rules-as-separate-module.md) | URL Rules in their own `rules` package, each tested on its own | Validation |
| [0005](docs/adr/0005-302-redirects-keep-us-in-the-path.md) | `302` redirects, so every Click passes through the shortener | Redirects |
| [0006](docs/adr/0006-htmx-progressive-enhancement-web-page.md) | Server-rendered Thymeleaf page, progressively enhanced with HTMX | Web page |
| [0007](docs/adr/0007-delivery-orchestrator.md) | Delivery orchestrator: a separate, development-time plane | Delivery |
| [0008](docs/adr/0008-orchestrator-stage-graph-and-governance.md) | Orchestrator stage graph and human approval model | Delivery |
| [0009](docs/adr/0009-orchestrator-state-audit-and-metrics.md) | Local state; hash-chained audit log in the repo; derived metrics | Delivery |
| [0010](docs/adr/0010-orchestrator-policy-guardrails.md) | Policy guardrails enforced before and after every step | Delivery |
| [0011](docs/adr/0011-orchestrator-replanning-and-lineage.md) | Re-planning through content-hash lineage | Delivery |
| [0012](docs/adr/0012-clicks-recorded-asynchronously.md) | Clicks recorded asynchronously, with bounded and measured loss | Analytics |
| [0013](docs/adr/0013-clicks-store-minimal-non-personal-data.md) | Clicks store minimal, non-personal data | Analytics, privacy |
| [0014](docs/adr/0014-creator-only-stats-via-manage-token.md) | Creator-only stats, authorised by a hashed manage token | Analytics, security |
| [0015](docs/adr/0015-observability-actuator-micrometer-structured-logs.md) | Health, metrics and JSON logs on a separate management port | Operability |
| [0016](docs/adr/0016-in-app-rate-limiting-per-client-ip.md) | In-app per-IP rate limits; IPs never stored | Security |
| [0017](docs/adr/0017-strict-content-security-policy-and-security-headers.md) | Strict, enforced Content-Security-Policy and security headers | Security |
| [0018](docs/adr/0018-private-address-rule-without-dns.md) | Private-address Rule, checked as written, with no DNS lookup | Security |
| [0019](docs/adr/0019-staged-evidence-triggered-scaling-path.md) | Staged scaling path, each stage triggered by measurements | Scalability |
| [0020](docs/adr/0020-parallel-lanes-fan-out-in-the-graph-waits-at-the-join.md) | Parallel Lanes: fan-out in the graph, waits held at the join | Delivery |
| [0021](docs/adr/0021-sqlite-wal-busy-timeout-and-small-connection-pool.md) | SQLite in WAL mode with a busy timeout and a pool of 4, so Redirects never wait behind Click writes | Storage |
| [0022](docs/adr/0022-expired-links-kept-short-codes-never-reused.md) | Expired Links are kept and their Short Codes are never reused | Expiring Links |
| [0024](docs/adr/0024-agent-model-and-effort-routed-per-step.md) | Agent model and effort routed per agent step | Delivery |

## Quick start

You only need **JDK 25** (e.g. Temurin); the Maven Wrapper downloads Maven and every dependency. For full setup, the repo tour and how work flows, read the **[onboarding guide](docs/onboarding.md)**.

```bash
git config core.hooksPath .githooks   # optional local guard; main is also protected on GitHub
./mvnw verify                         # format check, compile, static analysis, unit + integration tests
./mvnw spotless:apply                 # fix formatting if verify complains
(cd e2e && npm ci && BASE_URL=http://localhost:8000 npm run all)   # browser checks + Lighthouse; needs the app running and Chrome
./mvnw spring-boot:run                # start the app on http://localhost:8000 (foreground)
scripts/local.sh start                # or: build and run it in the background (stop | restart | status | logs)
docker compose up                     # or: run the container image on http://localhost:9000 (see the runbook)
```

Try it:

```bash
curl -s -X POST localhost:8000/links -H 'content-type: application/json' \
     -d '{"url": "https://example.com/very/long"}'
# 201 {"short_code":"mFzrymu","short_url":"http://localhost:8000/mFzrymu","long_url":"https://example.com/very/long",
#      "manage_token":"<43 random characters, shown only this once>","expires_at":null}
curl -si localhost:8000/mFzrymu    # 302, Location: https://example.com/very/long, Cache-Control: no-store
curl -s localhost:8000/links/mFzrymu/stats -H 'Authorization: Bearer <manage_token>'
# 200 {"short_code":"mFzrymu","short_url":"http://localhost:8000/mFzrymu","long_url":"https://example.com/very/long",
#      "created_at":"…","generated_at":"…","clicks":1,"bot_clicks":0,"last_click_at":"…",
#      "clicks_per_day":[{"date":"…","clicks":0}, … 30 UTC days, oldest first, today last: {"date":"…","clicks":1}],
#      "by_agent_category":{"browser":0,"other":1,"bot":0},   (curl counts as Agent Category other)
#      "by_device_class":{"desktop":1,"mobile":0},"top_referrer_hosts":[],"no_referrer_host":1}
# Without the right token, or for an unknown Short Code: the same 404 {"detail":"No stats found for this Short Code.", ...}

curl -s -X POST localhost:8000/links -H 'content-type: application/json' \
     -d '{"url": "https://example.com/offer", "expires_in_days": 30}'
# {"short_code":"Qp7tZ2w","short_url":"http://localhost:8000/Qp7tZ2w","long_url":"https://example.com/offer",
#  "manage_token":"<43 random characters>","expires_at":"2026-11-07T10:15:30.123Z"}
# From expires_at on: 410 Gone, "This link has expired."

curl -s -X POST localhost:8000/links -H 'content-type: application/json' \
     -d '{"url": "https://example.com/offer", "expires_in_days": "30"}'
# 422 {"detail":"expires_in_days must be a whole number of days from 1 to 365.", ...}; no Link is created

curl -s -X POST localhost:8000/links -H 'content-type: application/json' -d '{"url": "ftp://example.com"}'
# 422 {"detail":"Only http:// and https:// web addresses can be shortened.",
#      "instance":"/links","status":422,"title":"Unprocessable Content"}

curl -s localhost:8081/actuator/health/liveness    # 200 {"status":"UP","components":{"livenessState":{"status":"UP"}}}
curl -s localhost:8081/actuator/health/readiness   # 200 UP with db, clickQueue and readinessState; 503 while the database
                                                    # is unreachable or the Click queue is full
curl -s localhost:8081/actuator/prometheus         # metrics in Prometheus text
curl -si localhost:8000/actuator/health            # 404: no Actuator path exists on the public port
```

| Setting | Default | Purpose |
|---|---|---|
| `BASE_URL` | `http://localhost:8000` | The shortener's public address; every Short URL starts with it |
| `LOG_FORMAT` | `json` | Console log format: `json` (one ECS JSON object per line, with the `request_id`) or `text`; anything else stops startup |
| `DATABASE_PATH` | `links.db` | Where the SQLite database file lives (schema created by Flyway on startup). It runs in WAL mode, so `links.db-wal` and `links.db-shm` sit beside it: back up, move or delete the three together, and keep them on a local disk ([onboarding](docs/onboarding.md), ADR 0021) |
| `PORT` | `8000` | HTTP port |
| `MANAGEMENT_PORT` | `8081` | Management port for health checks and metrics, never the public port; only `/actuator/health` (Liveness, Readiness) and `/actuator/prometheus` are exposed (ADR 0015, spec 0006). A second local instance needs its own value as well as its own `PORT` |
| `CLICK_QUEUE_CAPACITY` | `10000` | Most Clicks waiting to be saved; any more are dropped and counted (ADR 0012) |
| `CLICK_BATCH_SIZE` | `500` | Most Clicks saved in one batch |
| `CLICK_FLUSH_INTERVAL` | `1s` | Longest the background writer waits for a batch to fill before it saves what it has; also the shortest gap between two dropped-Click warnings |
| `CLICK_SHUTDOWN_TIMEOUT` | `10s` | Longest a normal shutdown waits, after the web server stops, for queued Clicks to be saved; any still unsaved are dropped, counted and logged |

## Repository layout

```
src/main/java/…/shortener/   the service: controllers, LinkService, Link Store, Short Code generator
src/main/java/…/rules/       URL Rules and the Rule Set (ADR 0004)
src/main/java/…/clicks/      Clickstream (spec 0003): Click Classifier, Click Recorder (queue + batch writer), Click Store
src/main/java/…/stats/       Stats (spec 0005): Link Stats service and the JSON Stats endpoint
src/main/resources/          configuration, Flyway migrations, Thymeleaf templates, static assets
src/test/                    unit tests (*Test) and integration tests (*IT)
e2e/                         browser checks (Playwright) and Lighthouse audits
scripts/                     local.sh (run the app in the background), container-test.sh, openapi.sh, board-status.sh (delivery board)
docs/                        ADRs, specs, plans, roadmap, releases, architecture, onboarding
orchestrator/                the delivery orchestrator (Python, uv; built in Release 2)
delivery/                    the orchestrator's evidence: each Run's Event Log, report and tickets, and the metrics
Dockerfile · compose.yaml    the container image and its local Compose file
CONTEXT.md · CLAUDE.md       domain glossary · project charter
```

## How this project is run

| Artifact | Where | Purpose |
|---|---|---|
| Domain glossary | [`CONTEXT.md`](CONTEXT.md) | One shared vocabulary for code, tickets and docs |
| Architecture Decision Records | [`docs/adr/`](docs/adr/) | Significant decisions only, each with the options weighed and why one won |
| Roadmap | [`docs/roadmap.md`](docs/roadmap.md) | Every deferred item, prioritised into releases and tracked to completion |
| Architecture | [`docs/architecture.md`](docs/architecture.md) | The two planes, request flows, the Click flow and SQLite concurrency (Mermaid) |
| Onboarding | [`docs/onboarding.md`](docs/onboarding.md) | Setup, repo tour and how work flows, for a new engineer or reviewer |
| Specs | [`docs/specs/`](docs/specs/) | Numbered requirements with a status lifecycle; each traces to a roadmap item |
| Tickets | GitHub Issues, on the [delivery board](https://github.com/users/SanjuktaDavuluri/projects/1) and grouped by [milestone](https://github.com/SanjuktaDavuluri/simple-url-shortener/milestones) per release | Vertical-slice tickets; every change traces to one |
| Tests & CI | `src/test/`, GitHub Actions | Built test-first (TDD); unit + integration tests; CI gates every PR |
| Plans | [`docs/plans/`](docs/plans/) | How cross-cutting work is verified and delivered, e.g. the [integration-testing plan](docs/plans/0001-integration-testing.md) |
| Changelog and release notes | [`CHANGELOG.md`](CHANGELOG.md), [`docs/releases/`](docs/releases/) | One tagged version per Release, with notes: what shipped, evidence, what was deferred, lessons learned |
| Runbook | [`docs/runbook.md`](docs/runbook.md) | How to start, check, diagnose and stop the service, with its configuration reference |
| Incidents | *(introduced in a later Release)* | Operational evidence |

Artifacts are introduced **when their phase arrives**, so the history shows them being adopted rather than scaffolded up front. Work is driven with [Matt Pocock's engineering skills](https://github.com/mattpocock/skills) for Claude Code: grilling, domain modeling, spec, tickets, TDD and code review.

## Roadmap

| Release | Theme |
|---|---|
| 1 ✅ `v1.0.0` | Greenfield v1: build, all tests green, CI ([release notes](docs/releases/v1.0.0.md)) |
| 2 ✅ `v2.0.0` | Delivery orchestrator, click analytics, operability (health, metrics, logs, container, OpenAPI) ([release notes](docs/releases/v2.0.0.md)) |
| 3 | Hardening and reliability evidence: security gaps closed, risk register, load and failure testing, expiring links case study, scaling path, write-ups |
| later | PostgreSQL, horizontal scaling, custom aliases, edit/delete, and more, each with its reason for waiting |

Details, ordering rationale, status and the re-prioritisation log are in [`docs/roadmap.md`](docs/roadmap.md).
