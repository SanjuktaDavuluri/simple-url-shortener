# Simple URL Shortener

A small, backend-focused URL shortener, built in the open as a **complete SDLC case study**. The system is deliberately simple. The point is the *process*: every requirement, decision, change and incident is traceable from idea to production, through three phases:

1. **Greenfield**: from idea to a tested v1.
2. **Brownfield**: evolving the running system feature by feature.
3. **Reliability**: making it production-grade, with measurements.

> **Status:** Greenfield, wave 1, in progress. The JSON API can create a Link and Redirect from it ([#3](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/3)). Rules, Collision handling and the web page come in [#4–#8](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues).

## What it does (v1)

- **Shorten:** submit a Long URL and get back a Short URL such as `http://localhost:8000/Ab3xK9q`.
- **Redirect:** opening a Short URL sends you to its Long URL with a `302 Found`.
- **Web page:** a single server-rendered page to shorten links, with no full page reloads, copy-to-clipboard, and light and dark mode.

Terms such as *Link*, *Short Code* and *Rule* have precise meanings, defined in [`CONTEXT.md`](CONTEXT.md).

## Design at a glance

| Concern | Decision | Why it's recorded |
|---|---|---|
| Stack | Python 3.14, FastAPI, pytest, uv | [ADR 0001](docs/adr/0001-python-fastapi-stack.md) |
| Storage | SQLite behind a storage interface, ready for PostgreSQL | [ADR 0002](docs/adr/0002-sqlite-for-v1-storage.md) |
| Short Codes | 7 random base62 characters, independent of the Long URL, retried on Collision | [ADR 0003](docs/adr/0003-random-short-codes.md) |
| URL Rules | A separate `url_rules` module; each Rule is tested on its own | [ADR 0004](docs/adr/0004-url-rules-as-separate-module.md) |
| Redirects | `302`, so every Click passes through the shortener | [ADR 0005](docs/adr/0005-302-redirects-keep-us-in-the-path.md) |
| Web page | Jinja2 + HTMX progressive enhancement; Lighthouse ≥ 90 | [ADR 0006](docs/adr/0006-htmx-progressive-enhancement-web-page.md) |

Request flows are drawn as sequence diagrams in [`docs/architecture.md`](docs/architecture.md).

## Getting started

Requires [uv](https://docs.astral.sh/uv/) (it installs Python 3.14 if needed).

```bash
git config core.hooksPath .githooks          # optional local guard; main is also protected on GitHub
uv sync                                      # install dependencies
uv run pytest                                # run the test suite
uv run ruff check && uv run ruff format --check   # lint and formatting
uv run fastapi dev src/shortener/main.py     # start the app on http://localhost:8000
```

Try it:

```bash
curl -s -X POST localhost:8000/links -H 'content-type: application/json' \
     -d '{"url": "https://example.com/very/long"}'
# {"short_code":"Ab3xK9q","short_url":"http://localhost:8000/Ab3xK9q","long_url":"https://example.com/very/long"}
curl -si localhost:8000/Ab3xK9q    # 302 Found, Location: https://example.com/very/long
```

| Setting | Default | Purpose |
|---|---|---|
| `BASE_URL` | `http://localhost:8000` | The shortener's public address; every Short URL starts with it |
| `DATABASE_PATH` | `links.db` | Where the SQLite database lives |

## How this project is run

| Artifact | Where | Purpose |
|---|---|---|
| Domain glossary | [`CONTEXT.md`](CONTEXT.md) | One shared vocabulary for code, tickets and docs |
| Architecture Decision Records | [`docs/adr/`](docs/adr/) | Significant decisions only, each with the options weighed and why one won |
| Roadmap | [`docs/roadmap.md`](docs/roadmap.md) | Every deferred item, prioritised into waves and tracked to completion |
| Architecture | [`docs/architecture.md`](docs/architecture.md) | Request flows (Mermaid) |
| Specs | [`docs/specs/`](docs/specs/) | Numbered requirements with a status lifecycle; each traces to a roadmap item |
| Tickets | GitHub Issues | Vertical-slice tickets; every change traces to one |
| Tests & CI | `tests/`, GitHub Actions | Built test-first (TDD); CI gates every PR |
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
