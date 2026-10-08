# Simple URL Shortener: Project Charter

Read this before doing any work in this repo. It is the core brief and overrides default skill behaviour. The README is the map of every document; `docs/onboarding.md` has setup, the code tour and the repository's setup history.

## Goal

An **external third party will evaluate** this project against **SDLC standards**. The history must show the full lifecycle: **greenfield** (idea to tested v1), **brownfield** (changes traced to tickets and decisions), and **reliability** (observability, error handling, performance, incident and bug-fix discipline). Every piece of work leaves evidence a reviewer can follow: why it was decided, which ticket drove it, how it was tested.

## Two planes

The **product** is the Java URL shortener service (`java -jar`, `scripts/local.sh`, container). The **delivery orchestrator** (`orchestrator/`, Python, run on demand with `orchestrate`) is development-time tooling. It ends at a PR that is ready to merge, **never deploys and never connects to a running service**, and validates with temporary instances on their own port and data directory (ADR 0007, `docs/architecture.md`).

## How we work

- **One question at a time.** When grilling or clarifying, ask a single question with a recommended answer, then wait. Never a numbered round, even if a skill says so.
- **ADRs need explicit approval.** Only for decisions that are hard to reverse, surprising without context, and a real trade-off. Propose the candidate, ask "Record this as an ADR?", and write it only on a yes.
- **Every ADR records the trade-off:** each option on the table, what it gains and costs, and why the chosen one beat each alternative.
- **Matt Pocock skills drive the workflow** (`mattpocock-skills:*`: grilling, domain-modeling, to-spec, to-tickets, tdd, code-review, diagnosing-bugs). The rules here take precedence over their defaults.
- **Confirm a new kind of SDLC artifact before introducing it:** say which one and why, and get approval.
- **Branches and PRs only.** Conventional commits. One branch (`feat/`, `fix/`, `docs/`, `chore/`, `test/`, `refactor/` + `<issue>-<slug>`) and one PR per ticket, with `Closes #n`. Never commit to `main`; the maintainer merges.
- **`main` is protected.** Required checks: **"Verify (format, compile, analysis, unit + integration tests)"**, **"Browser checks (Playwright + Lighthouse)"** and **"Orchestrator (lint, types, tests)"**. Renaming a CI job breaks its required check, so update the protection in the same PR. Details are in `docs/onboarding.md`.
- **The maintainer's local service** (`scripts/local.sh start`, port 8000, data in `.local/`) may be in use. **Never stop, restart or reset it** without asking. For your own runs: `PORT=8765 DATA_DIR=<scratch dir> scripts/local.sh start`.
- **Keep the delivery board current** ([Project 1](https://github.com/users/SanjuktaDavuluri/projects/1); fields Status, Release, Kind). Use `scripts/board-status.sh <issue> "<status>"`: **In Progress** when the branch starts, **In Review** when the PR opens, **Done** after the merge (the board doesn't do this automatically). New issues: `gh project item-add 1 --owner SanjuktaDavuluri --url <issue-url>`, then set Release, Kind and the Release milestone.
- **Close out every Release.** Work is planned in numbered Releases (1, 2, 3, then *later*). When a Release's milestone closes, on a `docs/release-N-close-out` branch:
  - update every document it touched: the README, `docs/roadmap.md`, the spec index and statuses, `docs/architecture.md`, `docs/onboarding.md`, `CONTEXT.md`, and the Status below
  - tag `vN.0.0` on the completing commit and push only that tag (`git push origin vN.0.0`)
  - add a `CHANGELOG.md` entry and write release notes in `docs/releases/vN.0.0.md`: what shipped (with links), what was deferred and why, evidence, lessons learned, and what's next

## Approved SDLC artifacts

Introduce each one **only when its phase arrives**, so the history shows it being adopted.

| Phase | Artifact | Where |
|---|---|---|
| All | Specs (numbered, status lifecycle) | `docs/specs/`, indexed in its README |
| Greenfield | ADRs (approved, significant only) | `docs/adr/` |
| Greenfield | Domain glossary | `CONTEXT.md` |
| All | Roadmap and deferral log; items become specs (`/to-spec`) and tickets (`/to-tickets`) when picked up | `docs/roadmap.md` |
| All | Architecture diagrams (Mermaid) | `docs/architecture.md` |
| All | Onboarding guide; the README is the reviewer's map | `docs/onboarding.md`, `README.md` |
| All | Plans (numbered, tracked by an issue labelled `plan`) | `docs/plans/` |
| All | Tickets, each linking its spec | GitHub Issues |
| All | Branch + PR per ticket, conventional commits | GitHub |
| All | TDD tests, CI | GitHub Actions |
| All | CHANGELOG, version tag and release notes per Release | `CHANGELOG.md`, `vN.0.0`, `docs/releases/` |
| Reliability | Runbook, incident and bug write-ups, observability | `docs/runbook.md`, `docs/incidents/` |

## Status

- **Release 1 complete** (`v1.0.0`, spec 0001). **Release 2 in progress** (milestone 2):
  - **Done:** the delivery orchestrator (R18, spec 0002, tickets #26–#35), and the Clickstream (R10, spec 0003, ADR 0021), delivered by orchestrator Run R-0001 (#49, close-out #71; evidence in `delivery/runs/R-0001/` and `delivery/metrics.md`). The engineering summary is in `docs/summary.md` (#72).
  - **Open:** orchestrator fixes found in R-0001: #74–#79.
  - **Next, in roadmap order, each as an orchestrator Run:** R2 ∥ R12 → R11 → R21, then the Release 2 close-out.
- Release 3 is designed (ADRs 0016–0019; approaches in `docs/roadmap.md`).
- **Service:** Java 25 + Spring Boot 4.1.1, Maven Wrapper; SQLite via `JdbcClient` with Flyway (ADRs 0001–0006). Base package `io.github.sanjuktadavuluri.shortener` (URL Rules in `rules`). `BASE_URL` builds Short URLs and drives the no-self-link Rule.
- **Service tests:** `./mvnw verify` is the single entry point (Spotless, Error Prone, JUnit 5 + AssertJ). `*Test` are unit tests and `*IT` are integration tests extending `IntegrationTest`: a shared context, the database reset before each test, a scripted `ShortCodeGenerator`, and extra instances via `TestApps`. Update the matrix in `docs/plans/0001-integration-testing.md` in each ticket's PR. Browser checks and Lighthouse live in `e2e/` (median of 3 runs, ≥ 90).
- **Orchestrator:** Python 3.13 + uv, LangGraph, Claude Agent SDK (from #35). Tests drive the `orchestrate` command with a scripted agent and an in-memory GitHub, plus the policy check. Run `ruff`, `mypy --strict` and `pytest`. See `orchestrator/README.md`.

## Agent skills

### Issue tracker

Specs are committed in `docs/specs/`; tickets are GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

The default five roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `CONTEXT.md` + `docs/adr/`. See `docs/agents/domain.md`.
