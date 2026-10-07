# Simple URL Shortener

A small, backend-focused URL shortener, built in the open as a **complete SDLC case study**. The system is deliberately simple. The point is the *process*: every requirement, decision, change and incident is traceable from idea to production, through three phases:

1. **Greenfield**: from idea to a tested v1.
2. **Brownfield**: evolving the running system feature by feature.
3. **Reliability**: making it production-grade, with measurements.

> **Status:** **Wave 1 (greenfield) complete.** [Spec 0001](docs/specs/0001-v1-core.md) is implemented: the JSON API (create, Redirect, URL Rules, Collision handling) and the web page (works with or without JavaScript; Lighthouse 100 in every category). Wave 2 (production readiness) is next on the [roadmap](docs/roadmap.md). **Track the work:** [delivery board](https://github.com/users/SanjuktaDavuluri/projects/1) · [milestones](https://github.com/SanjuktaDavuluri/simple-url-shortener/milestones).

<p>
  <img src="docs/images/web-page-light-desktop.png" alt="The web page in light mode on a desktop, showing a newly created Short URL with a Copy button" width="560">
  <img src="docs/images/web-page-dark-phone-rejected.png" alt="The web page in dark mode on a phone, showing the Rejection Reason under the Long URL field" width="200">
</p>

## What it does (v1)

- **Shorten:** submit a Long URL and get back a Short URL such as `http://localhost:8000/Ab3xK9q`.
- **Redirect:** opening a Short URL sends you to its Long URL with a `302 Found`.
- **Web page:** a single server-rendered page to shorten links, with no full page reloads, copy-to-clipboard, and light and dark mode.

Terms such as *Link*, *Short Code* and *Rule* have precise meanings, defined in [`CONTEXT.md`](CONTEXT.md).

## Design at a glance

| Concern | Decision | Why it's recorded |
|---|---|---|
| Stack | Java 25 (LTS), Spring Boot 4.1.1, Maven Wrapper | [ADR 0001](docs/adr/0001-java-spring-boot-stack.md) |
| Storage | SQLite via `JdbcClient`, schema by Flyway, behind a Link Store interface ready for PostgreSQL | [ADR 0002](docs/adr/0002-sqlite-for-v1-storage.md) |
| Short Codes | 7 random base62 characters, independent of the Long URL, retried on Collision | [ADR 0003](docs/adr/0003-random-short-codes.md) |
| URL Rules | A separate `rules` package; each Rule is tested on its own | [ADR 0004](docs/adr/0004-url-rules-as-separate-module.md) |
| Redirects | `302`, so every Click passes through the shortener | [ADR 0005](docs/adr/0005-302-redirects-keep-us-in-the-path.md) |
| Web page | Thymeleaf + HTMX progressive enhancement; Lighthouse ≥ 90 | [ADR 0006](docs/adr/0006-htmx-progressive-enhancement-web-page.md) |

Request flows are drawn as sequence diagrams in [`docs/architecture.md`](docs/architecture.md).

## Getting started

You only need **JDK 25** (e.g. Temurin); the Maven Wrapper downloads Maven and every dependency.

```bash
git config core.hooksPath .githooks   # optional local guard; main is also protected on GitHub
./mvnw verify                         # format check, compile, static analysis, unit + integration tests
./mvnw spotless:apply                 # fix formatting if verify complains
(cd e2e && npm ci && BASE_URL=http://localhost:8000 npm run all)   # browser checks + Lighthouse; needs the app running and Chrome
./mvnw spring-boot:run                # start the app on http://localhost:8000 (foreground)
scripts/local.sh start                # or: build and run it in the background (stop | restart | status | logs)
```

Try it:

```bash
curl -s -X POST localhost:8000/links -H 'content-type: application/json' \
     -d '{"url": "https://example.com/very/long"}'
# {"short_code":"mFzrymu","short_url":"http://localhost:8000/mFzrymu","long_url":"https://example.com/very/long"}
curl -si localhost:8000/mFzrymu    # 302, Location: https://example.com/very/long, Cache-Control: no-store

curl -s -X POST localhost:8000/links -H 'content-type: application/json' -d '{"url": "ftp://example.com"}'
# 422 {"detail":"Only http:// and https:// web addresses can be shortened.",
#      "instance":"/links","status":422,"title":"Unprocessable Content"}
```

| Setting | Default | Purpose |
|---|---|---|
| `BASE_URL` | `http://localhost:8000` | The shortener's public address; every Short URL starts with it |
| `DATABASE_PATH` | `links.db` | Where the SQLite database file lives (schema created by Flyway on startup) |
| `PORT` | `8000` | HTTP port |

## How this project is run

| Artifact | Where | Purpose |
|---|---|---|
| Domain glossary | [`CONTEXT.md`](CONTEXT.md) | One shared vocabulary for code, tickets and docs |
| Architecture Decision Records | [`docs/adr/`](docs/adr/) | Significant decisions only, each with the options weighed and why one won |
| Roadmap | [`docs/roadmap.md`](docs/roadmap.md) | Every deferred item, prioritised into waves and tracked to completion |
| Architecture | [`docs/architecture.md`](docs/architecture.md) | Request flows (Mermaid) |
| Specs | [`docs/specs/`](docs/specs/) | Numbered requirements with a status lifecycle; each traces to a roadmap item |
| Tickets | GitHub Issues, on the [delivery board](https://github.com/users/SanjuktaDavuluri/projects/1) and grouped by [milestone](https://github.com/SanjuktaDavuluri/simple-url-shortener/milestones) per wave | Vertical-slice tickets; every change traces to one |
| Tests & CI | `src/test/`, GitHub Actions | Built test-first (TDD); unit + integration tests; CI gates every PR |
| Plans | [`docs/plans/`](docs/plans/) | How cross-cutting work is verified and delivered, e.g. the [integration-testing plan](docs/plans/0001-integration-testing.md) |
| Changelog, runbook, incidents | *(introduced in later phases)* | Release history and operational evidence |

Artifacts are introduced **when their phase arrives**, so the history shows them being adopted rather than scaffolded up front. Work is driven with [Matt Pocock's engineering skills](https://github.com/mattpocock/skills) for Claude Code: grilling, domain modeling, spec, tickets, TDD and code review.

## Roadmap

| Wave | Theme |
|---|---|
| 1 | Greenfield v1: build, all tests green, CI |
| 2 | Production readiness: Docker, health checks, structured logs, config, runbook, network-safety Rule |
| 3 | Reliability & scalability: load-test baseline → PostgreSQL → failure testing → horizontal scaling |
| 4 | Feature evolution: clickstream audit, click counts, custom aliases, expiry, edit/delete |

Details, ordering rationale and status are in [`docs/roadmap.md`](docs/roadmap.md).
