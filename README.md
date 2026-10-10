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

### Available now (v1 and Release 2, tagged [`v1.0.0`](docs/releases/v1.0.0.md) and [`v2.0.0`](docs/releases/v2.0.0.md))

**Release 1 ([spec 0001](docs/specs/0001-v1-core.md))**

- **Shorten:** submit a Long URL and get back a Short URL such as `http://localhost:8000/Ab3xK9q`, through the JSON API (`POST /links`) or the web page.
- **Redirect:** opening a Short URL sends you to its Long URL with a `302 Found`.
- **URL Rules:** only `http`/`https` addresses with a host, at most 2048 characters, and never a link back to the shortener itself. Each refusal comes with a clear Rejection Reason (`422`).
- **Web page:** a single server-rendered page with no full page reloads, copy-to-clipboard, and light and dark mode. It works with JavaScript off.

**Release 2 ([release notes](docs/releases/v2.0.0.md))**

- **Clickstream** (R10, [spec 0003](docs/specs/0003-clickstream.md)): every successful Redirect records one Click, saved in batches by a background writer so the Redirect never waits for the database ([ADR 0012](docs/adr/0012-clicks-recorded-asynchronously.md), [ADR 0013](docs/adr/0013-clicks-store-minimal-non-personal-data.md)).
- **Expiring Links** (R3, [spec 0004](docs/specs/0004-expiring-links.md)): an optional Lifetime of 1 to 365 days; an Expired Link answers `410 Gone` and its Short Code is never reused ([ADR 0022](docs/adr/0022-expired-links-kept-short-codes-never-reused.md)).
- **Click stats per Link** (R2, [spec 0005](docs/specs/0005-click-stats-per-link.md)): a Manage Token returned once on create, the Stats JSON API (`GET /links/{short_code}/stats`) and a `/stats` web page ([ADR 0014](docs/adr/0014-creator-only-stats-via-manage-token.md), [ADR 0023](docs/adr/0023-manage-token-hashed-with-sha-256.md)).
- **Operability basics** (R12, [spec 0006](docs/specs/0006-operability-basics.md)): a Request ID on every response, Liveness and Readiness on a separate management port, structured JSON logs, Prometheus metrics, graceful shutdown and the first [runbook](docs/runbook.md) ([ADR 0015](docs/adr/0015-observability-actuator-micrometer-structured-logs.md)).
- **Container image** (R11, [spec 0007](docs/specs/0007-dockerize.md)): a multi-stage image that runs as a non-root user, with Compose for a local run and a container test job in CI.
- **OpenAPI definition** (R21, [spec 0008](docs/specs/0008-openapi-definition.md)): [`docs/api/openapi.yaml`](docs/api/openapi.yaml), generated from the code with `scripts/openapi.sh` and guarded by a drift test.
- **The delivery orchestrator** (R18, [spec 0002](docs/specs/0002-delivery-orchestrator.md)): see [below](#the-delivery-orchestrator).

How it works: the [architecture](docs/architecture.md), the [glossary](CONTEXT.md), the code tour in [onboarding](docs/onboarding.md) and the test evidence in [plan 0001](docs/plans/0001-integration-testing.md).

### Planned ([roadmap](docs/roadmap.md))

| Release | Feature | Decided in |
|---|---|---|
| 3 | Hardening: rate limits, strict security headers, blocking private and internal addresses | ADRs [0016](docs/adr/0016-in-app-rate-limiting-per-client-ip.md), [0017](docs/adr/0017-strict-content-security-policy-and-security-headers.md), [0018](docs/adr/0018-private-address-rule-without-dns.md) |
| 3 | Reliability evidence: load-test baseline against SLOs, failure-mode testing, scaling path | [ADR 0019](docs/adr/0019-staged-evidence-triggered-scaling-path.md) · roadmap R13, R14 |
| 3 | Requirement-clarification case study write-up (R23); the feature it clarified, Expiring Links (R3), is already done above | roadmap R23, R24 |

## The delivery orchestrator

The stage graph the orchestrator runs (`orchestrator/src/orchestrator/graph.py`). Each diagram is one level of detail down from the one before.

### Run flow

```text
 START
   │
   ▼
┌────────┐ pass ┌──────────────┐   ┌────────┐   ┌───────────┐   ┌───────┐
│ intake │─────▶│ requirements │──▶│ design │──▶│ decompose │──▶│ lanes │
└───┬────┘      └──────────────┘   └────────┘   └───────────┘   └───┬───┘
    │ fail        (Stage template: see below)                       │
    ▼                                                               ▼
   END                  END ◀── ┌───────────┐ ◀── ┌───────────────────┐
                                │ close_out │     │ release_readiness │
                                └───────────┘     └───────────────────┘
```

### Stage template (requirements, design, decompose)

```text
  ┌────────────── rejected (reason fed back) ──────────────┐
  │                                                        │
  ▼                                                        │
┌───────┐       ┌──────┐ pass ┌─────────┐   ┌───────┐   ┌──┴──────┐
│ draft │──────▶│ gate │─────▶│ request │──▶│ await │──▶│ decided │
└───────┘       └──┬───┘      └─────────┘   └───────┘   └────┬────┘
  ▲  ▲             │ fail                                    │ approved
  │  └─ retry ─────┤ (feedback)                              ▼
  │                │ retries used up                    next Stage
  │                ▼
  │            ┌────────┐
  └─ resume ───│ paused │
               └────────┘
```

| Node | What it does |
|---|---|
| draft | The agent produces the artifact (spec, ADR or ticket breakdown). |
| gate | Checks the draft. On failure it retries with the problems as feedback, until `max_retries` is used up. |
| request | Posts the approval request once, with the artifact link and hash. |
| await | Stops the Run until a human approves or rejects. |
| decided | Routes on the decision: forward if approved, back to draft if rejected. |
| paused | Entered when retries run out. It waits for `orchestrate resume`, then retries the draft. |

Each stage differs slightly:

- **requirements:** draft can also ask the user questions (`await_answer`) before the gate.
- **design:** there is one approval per ADR, and zero ADRs is allowed. After a decision it either requests the next ADR's approval or goes to `done`.
- **decompose:** after approval it goes to `publish`, which creates the tickets, then to `lanes`.

### Lanes (implementation)

```text
┌───────┐   ┌──────────────────────────┐
│ lanes │──▶│ Docs PR: checks ─▶ merge │  (waits for maintainer;
└───┬───┘   └────────────┬─────────────┘   failure ─▶ paused ─▶ retry)
    │ no docs PR         │ merged
    ▼                    ▼
  ┌──────────────────────────────┐
  │        lanes_schedule        │◀──────── every spoke returns here
  └──────────────┬───────────────┘
                 │ picks the next action
   ┌─────────────┼───────────────┬─────────────┬──────────────┐
   ▼             ▼               ▼             ▼              ▼
lane_work     lanes_wait     lane_paused    rollback      approvals*
 (run a lane,  (others still  (lane failed;  (undo a lane)  dependency or
  then join)    running)       wait for                      amendment
                               engineer)                     request▶await▶decided
                 │
                 │ all lanes finished
                 ▼
            lanes_done ───▶ release_readiness
```

\* Both approvals use the same request, await, decided chain as the Stage template.

### Release readiness and re-plan

```text
lanes_done ─▶ release_readiness ──▶ close_out ──▶ END
                   ▲      │
                   │      │ checks fail
                   │      ▼
                   └── readiness_paused    (resume ─▶ re-check)

replan_detected ─▶ replan ─▶ requirements | design | decompose | decompose_gate
(entered only when an input changed; the Run restarts at the first affected Stage)
```

### Reading guide

- **Wait for a human:** `await` nodes use `interrupt()`. They save a checkpoint and the process exits. A later `approve`, `reject` or `resume` continues the Run.
- **Pause and retry:** every `paused` node waits for `resume`, then re-runs the step that failed.
- **Safe-stop:** a Safe-stop or `stop` label can halt the Run before any node. It continues with `orchestrate resume`.

### Orchestrator learnings

- **Match the model to the stage.** The first version used Opus 5.5 for every stage, and the token allowance ran out about every four hours. The orchestrator now uses Opus, Sonnet or Haiku depending on the stage of the graph, which greatly reduced how often the limit was hit.
- **Run the orchestrator directly.** Starting it with `orchestrate` yourself, instead of asking another agent to kick it off, saved a significant number of tokens, because no second agent has to read and relay the Run's output.
- **Waiting on a human is the main remaining cost.** While a PR is waiting to be merged or an approval is pending, the orchestrator spends effort checking GitHub for the answer. A future version could push notifications to Slack and receive a webhook when the engineer acts, so the Run resumes on the event and no longer polls GitHub while it waits for developer input.

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
