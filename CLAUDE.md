# Simple URL Shortener: Project Charter

Read this before doing any work in this repo. It is the core brief and overrides default skill behaviour.

## Goal

An **external third party will evaluate** this project. They will judge whether it was built in line with **SDLC standards**, and whether its history shows the full lifecycle:

1. **Greenfield**: from idea to first working version (requirements, design decisions, initial implementation, tests).
2. **Brownfield**: changing the existing system (new features, refactors, migrations), tracing each change to a ticket or decision.
3. **Reliability**: running it like a production system (observability, error handling, performance, incident and bug-fix discipline).

Every piece of work should leave evidence a reviewer can follow: why something was decided, which ticket drove a change, and how it was tested.

## Two planes (read first)

This repo holds the **product** (the Java URL shortener service: `java -jar`, `scripts/local.sh`, container) and, from wave 2, the **delivery orchestrator** (`orchestrator/`, Python, run on demand with `orchestrate`). The orchestrator is development-time tooling. It ends at a PR that is ready to merge and **never deploys or connects to a running service**. Its validation stages start their own temporary instances on another port and data directory, never the maintainer's service on :8000. See ADR 0007 and the diagram in `docs/architecture.md`.

## How we work

- **Interview one question at a time.** When grilling or clarifying, ask a single question, give a recommended answer, and wait. Don't batch questions, even if a skill says to ask a whole round.
- **ADRs need explicit user approval.** Record only significant architectural decisions: hard to reverse, surprising without context, and the result of a real trade-off. When a decision qualifies, propose it as an ADR candidate and ask "Record this as an ADR?" Write it only on a yes. Never record trivial choices.
- **Every ADR records the trade-off, not just the decision.** List each option that was on the table, what it would gain and cost, and why the chosen one beat each alternative ("A over B because…"). A reviewer must be able to see what we chose over what.
- **Matt Pocock skills drive the workflow** (`mattpocock-skills:*`, e.g. grilling, domain-modeling, to-spec, to-tickets, tdd, code-review, diagnosing-bugs). The rules above take precedence over those skills' defaults.
- **The maintainer's local service.** `scripts/local.sh start` runs the app in the background on port 8000, with data in `.local/`. The maintainer may be testing against it, so **never stop, restart or reset it** without asking. For your own runs use another port and data dir, e.g. `PORT=8765 DATA_DIR=<scratch dir> scripts/local.sh start`.
- **Keep the delivery board current.** Every ticket lives on the GitHub Project [Simple URL Shortener: Delivery](https://github.com/users/SanjuktaDavuluri/projects/1) (fields: Status, Wave, Kind) and in its wave's milestone. Move it with `scripts/board-status.sh <issue> "<status>"`: **In Progress** when its branch starts, **In Review** when its PR opens. Then move it to **Done** after its PR merges and the issue closes. The board's built-in "closed → Done" automation isn't enabled, so this step is manual. New issues: add them to the board (`gh project item-add 1 --owner SanjuktaDavuluri --url <issue-url>`), set Wave and Kind, and set the milestone.
- **Confirm SDLC artifacts before using them.** Before introducing a new kind of artifact (ADRs, tickets, specs, changelog, CI and so on), say which one and why, and get approval.
- **Commits:** conventional commits. **All work goes on a feature branch** (`feat/…`, `fix/…`, `docs/…`, `chore/…`, `test/…`, `refactor/…`) and reaches `main` only through a PR, with one branch + PR per ticket. Never commit straight to `main`.
- **`main` protection (2026-10-07):** **GitHub branch protection** is on: the CI check **"Verify (format, compile, analysis, unit + integration tests)"** and **"Browser checks (Playwright + Lighthouse)"** must both pass (renaming either CI job breaks its required check, so update the protection in the same PR), a PR is required (0 approvals, since this is a solo project and you can't approve your own PR), conversations must be resolved, admins are included, and force-pushes and deletion are blocked. A versioned `pre-push` hook in `.githooks/` is a **local backup** that refuses pushes to `main` before they reach GitHub; enable it once per clone with `git config core.hooksPath .githooks`. History: protection returned 403 while the repo was private on a free plan; the repo was made public (and rebuilt with a no-reply commit email first) so that protection could be enforced by GitHub. The first commit is the design docs, made before any code.

## Approved SDLC artifacts

Approved 2026-10-07. Introduce each one **only when its phase arrives**, so the history shows it being adopted at that point:

| Phase | Artifact | Location / form |
|---|---|---|
| All | Specs (numbered, with status lifecycle) | `docs/specs/NNNN-<slug>.md`, indexed in `docs/specs/README.md` (decided 2026-10-07) |
| Greenfield | ADRs (user-approved, significant only) | `docs/adr/NNNN-*.md` |
| Greenfield | Domain glossary | `CONTEXT.md` |
| All | Roadmap / deferral log: every postponed item is logged, then turned into a spec (`/to-spec`) and tickets (`/to-tickets`) when picked up | `docs/roadmap.md` |
| All | Architecture diagrams (Mermaid) | `docs/architecture.md` |
| All | Onboarding guide for new engineers and reviewers; README is the reviewer's map | `docs/onboarding.md`, `README.md` (added 2026-10-07) |
| All | Plans (numbered, with status; tracked by a GitHub issue labelled `plan`) | `docs/plans/NNNN-<slug>.md`, indexed in `docs/plans/README.md` (added 2026-10-07) |
| All | Tickets | GitHub Issues in the private repo, each linking to its spec (decided 2026-10-07) |
| All | Git history: branch + PR per ticket, conventional commits | Private GitHub repo, created when prototyping ends |
| All | TDD tests, CI (lint + tests) | GitHub Actions |
| Brownfield | CHANGELOG + versioned releases | `CHANGELOG.md` |
| Reliability | Runbook, incident/bug write-ups, observability (logs, health check, metrics) | `docs/runbook.md`, `docs/incidents/` |

Hosting: **private GitHub repo** [SanjuktaDavuluri/simple-url-shortener](https://github.com/SanjuktaDavuluri/simple-url-shortener) with Issues and PRs. Created 2026-10-07, before wave-1 implementation, so tickets exist before their code. This replaced the earlier plan to stay local until wave 1 ended. One branch + PR per ticket; the PR body says `Closes #n`.

## Status

- Phase: **Wave 1 (greenfield) complete** with #8: spec 0001 implemented. **Wave 2 (production readiness) starts only when the user says so**: then ticket roadmap items R11 → R12 → R6 → R17 (`/to-spec` for their spec, `/to-tickets` for issues), with a `Wave 2` milestone and board entries. CI has two jobs: **Verify** (`./mvnw verify`, required) and **Browser checks** (`e2e/`: Playwright + Lighthouse ≥ 90).
- v1 scope (decided 2026-10-07): **core only**, i.e. shorten a long URL to a short code and redirect from it, via a JSON API plus a minimal web page. Planned brownfield features: custom aliases, click counts, expiring links. Accounts are out of scope.
- Stack: **Java 25 (LTS) + Spring Boot 4.1.1**, built with the **Maven Wrapper** (`./mvnw`). See ADR 0001.
- Data: SQLite through Spring `JdbcClient` (plain SQL, no ORM); schema by **Flyway** migrations. See ADR 0002.
- Tests & quality: JUnit 5 + AssertJ; unit tests `*Test` (Surefire), integration tests `*IT` (Failsafe) through `@SpringBootTest` + `MockMvcTester` extending `IntegrationTest` (shared context reset before every test: Flyway clean + migrate, empty Short Code script; shared request helpers; extra instances via `TestApps`; databases under `target/`) and a scripted `ShortCodeGenerator` bean; Spotless (google-java-format) + Error Prone. `./mvnw verify` is the single local and CI entry point. Integration testing follows `docs/plans/0001-integration-testing.md`: update its matrix in each ticket's PR.
- Java packages: base `io.github.sanjuktadavuluri.shortener`; Rules live in its `rules` sub-package.
- Storage: **SQLite**, kept behind one storage interface so a later move to PostgreSQL is a contained change. See ADR 0002.
- Short codes: **random 7-char base62, retry on collision**. See ADR 0003. Specified in `docs/specs/0001-v1-core.md`.
- URL validation v1 (decided 2026-10-07): http/https with a host, at most 2048 chars, and links back to our own domain are refused. Rules live as a **separate rule entity**, not inline in the pipeline. See ADR 0004 (separate `rules` package).
- Duplicate long URLs: **a new link every time**. This follows from ADR 0003. Flows are in `docs/architecture.md`.
- Redirects: **302 Found**, so every click passes through us (audit and clickstream foundation). See ADR 0005.
- Web page: **server-rendered (Spring MVC + Thymeleaf)**, because this is a backend-focused project. It calls the same create-link logic as the API, with no duplicate path. Quality bar: it must look and feel **current state of the art**, not a bare form. HTMX progressive enhancement plus 6 acceptance criteria (Lighthouse ≥ 90). See ADR 0006.
- Public address: the `BASE_URL` setting (default `http://localhost:8000`) builds short links and drives the "no links to ourselves" rule.

## Agent skills

### Issue tracker

Specs: committed in `docs/specs/`. Tickets: GitHub Issues in the private repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line while local. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `CONTEXT.md` + `docs/adr/`. See `docs/agents/domain.md`.
