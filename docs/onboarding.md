# Onboarding

A guide for an engineer joining the project, or a reviewer who wants to run it and follow how it was built. Allow about 30 minutes. The [README](../README.md) is the map of every document. This page covers setting up, finding your way around, and making a change.

## 1. Read these first (10 minutes)

1. **[README](../README.md):** what the product does, the two planes (the product and the delivery orchestrator), and links to everything else.
2. **[`CONTEXT.md`](../CONTEXT.md):** the domain vocabulary. Code, tickets and docs all use these terms (*Link*, *Long URL*, *Short Code*, *Rule*, *Redirect*, *Click*), so learn them before reading code.
3. **[`docs/architecture.md`](architecture.md):** the two planes, then the create and Redirect flows as sequence diagrams.
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

To run a second, throwaway copy next to it, give it its own port and data directory: `PORT=8765 DATA_DIR=/tmp/shortener-scratch scripts/local.sh start`. The same pattern keeps automated runs away from your service.

| Setting | Default | Purpose |
|---|---|---|
| `BASE_URL` | `http://localhost:8000` | The public address; every Short URL starts with it |
| `DATABASE_PATH` | `links.db` | The SQLite database file (schema created by Flyway on startup) |
| `PORT` | `8000` | HTTP port |

Browser checks and Lighthouse, against a running app:

```bash
(cd e2e && npm ci && BASE_URL=http://localhost:8000 npm run all)
```

## 4. Find your way around the code

| Where | What |
|---|---|
| `LinkController`, `PageController` | The JSON API (`POST /links`, `GET /{shortCode}`) and the web page (`GET /`, `POST /`) |
| `LinkService` | Create-a-Link: run the Rule Set, draw Short Codes, retry on Collision |
| `rules/` | One class per URL Rule, plus the ordered `RuleSet` ([ADR 0004](adr/0004-url-rules-as-separate-module.md)) |
| `LinkStore`, `JdbcLinkStore` | Storage interface and its SQLite implementation ([ADR 0002](adr/0002-sqlite-for-v1-storage.md)) |
| `ShortCodeGenerator`, `RandomShortCodeGenerator` | 7-character base62 codes ([ADR 0003](adr/0003-random-short-codes.md)) |
| `src/main/resources/db/migration/` | Flyway migrations; the schema only changes through a new migration |
| `src/main/resources/templates/`, `static/` | Thymeleaf page and fragments, CSS, self-hosted HTMX ([ADR 0006](adr/0006-htmx-progressive-enhancement-web-page.md)) |
| `src/test/` | `*Test`: unit tests. `*IT`: integration tests that extend `IntegrationTest`, which resets the database before every test and controls Short Codes through a scripted generator |
| `e2e/` | Playwright browser checks and Lighthouse (≥ 90 in every category) |
| `orchestrator/` | The delivery orchestrator (Python, uv): setup, commands and tests in [`orchestrator/README.md`](../orchestrator/README.md); design in [ADRs 0007–0011](adr/0007-delivery-orchestrator.md) |

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

## 7. The delivery orchestrator (from Release 2)

The orchestrator automates the chain in section 5 under human control. It's a separate, development-time tool: it ends at a PR that's ready to merge, never deploys, and never connects to a running service. The design is in [ADRs 0007–0011](adr/0007-delivery-orchestrator.md). Usage instructions will be added here when it's built.
