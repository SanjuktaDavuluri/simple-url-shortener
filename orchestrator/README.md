# Delivery orchestrator

The **delivery plane** of this repository: a development-time command-line tool that drives a GitHub Issue through gated Stages to merge-ready pull requests, under human control. It never deploys, and it never connects to a running service (ADR 0007).

- **Spec:** [0002](../docs/specs/0002-delivery-orchestrator.md) · **Decisions:** ADRs [0007](../docs/adr/0007-delivery-orchestrator.md)–[0011](../docs/adr/0011-orchestrator-replanning-and-lineage.md) · **Vocabulary:** the Delivery section of [`CONTEXT.md`](../CONTEXT.md)
- **Status:** intake (#26), **requirements** with the spec approval (#27), **design** with one approval per ADR and **decompose** with the ticket approval and publishing (#28). Lanes and the rest arrive with tickets #29–#35 (Release 2).

## Setup

Requires [uv](https://docs.astral.sh/uv/) (it installs Python 3.13 itself) and the GitHub CLI logged in (`gh auth login`).

```bash
cd orchestrator
uv sync
uv run orchestrate --help
```

Run it from anywhere inside the repository; it finds the repository root with git.

## Commands available now

| Command | What it does |
|---|---|
| `orchestrate start <issue>` | Starts a Run (`R-NNNN`) for the Issue and runs intake: records the Issue with content hashes and links the roadmap item it names. Then the requirements Stage asks its first clarifying question on the Issue, or writes the spec. Refuses an Issue that already has an active Run |
| `orchestrate status [run]` | Without a Run: lists every Run. With one: its Stages, what it is waiting for (an answer, an approval), and cost so far |
| `orchestrate resume <run>` | Continues a Run: reads your reply to the open question, or a maintainer's `approved:<checkpoint>` label added after the approval was requested. Does nothing (and says what it is waiting for) when neither is there yet |
| `orchestrate approve <run> <checkpoint>` | Approves `spec`, `adr-NNNN` (one per ADR) or `tickets`. The approval is bound to the content hash that was submitted; if the file changed since, it is refused |
| `orchestrate reject <run> <checkpoint> --reason "…"` | Rejects with a reason; the Stage revises and asks for approval again (an earlier label no longer counts) |
| `orchestrate verify <run>` / `--all` | Checks that Event Logs are intact (sequence, each event's hash, the link to the previous event) and names the first broken event |

## How a Run talks to you

- **On the Issue.** Every orchestrator comment carries a hidden `<!-- orchestrator:R-NNNN -->` marker, so its own comments are never read as your answers. Questions come one at a time with a recommended answer: reply in a comment, then run `orchestrate resume`.
- **On its own branch.** The spec (and, from #28, ADRs) is written in a separate git worktree on `docs/run-R-NNNN`, branched from `origin/main` and pushed, so the approval request links to the file on GitHub. Your checkout and `main` are never touched.
- **Exits while waiting.** No process stays running: a command returns as soon as the Run needs a human.
- **Spec Exit Gate.** A new `docs/specs/NNNN-<slug>.md` with every template section, numbered user stories, non-empty testing decisions, and the Run's roadmap item in its frontmatter. A failing gate is fed back to the agent up to `max_retries` times; then the Stage fails and the Run pauses.

## The Stages so far

| Stage | What it produces | Exit Gate | Approval Checkpoint |
|---|---|---|---|
| intake | The Issue's content hashes and its roadmap item | The Issue has a request | — |
| requirements | Clarifying Q&A on the Issue, then `docs/specs/NNNN-<slug>.md` | Template sections, numbered user stories, testing decisions, roadmap link | `spec` |
| design | ADRs at `docs/adr/NNNN-<slug>.md` (`status: proposed`), or "no ADR" with a reason | Options table with gains and costs, exactly one **Chosen**, Rejected alternatives, Consequences, a number not used on `main`; accepted ADRs never change | `adr-NNNN`, one per ADR; approval marks it `accepted` |
| decompose | `delivery/runs/R-NNNN/tickets.json`: vertical slices with blocking edges | At least one ticket; unique keys; acceptance criteria; a known kind; blockers that exist; no cycles | `tickets`; approval publishes the Issues blockers first, with `ready-for-agent`, the Release milestone, the board entry and native "blocked by" links |

A failing gate goes back to the agent with its problems, up to `max_retries` times; then the Stage fails and the Run pauses. A rejection goes back with its reason, and the revision needs a fresh approval.

## Where things are kept

| What | Where | Committed? |
|---|---|---|
| Run state (LangGraph checkpoints), in-progress Event Logs and Run worktrees | `.orchestrator/` at the repository root | No (gitignored) |
| Finished Runs' Event Logs and reports | `delivery/runs/R-NNNN/` | Yes, through each Run's close-out PR (from #29) |
| Settings: retries, parallel Lanes, cost caps, model, delivery board | [`settings.yaml`](settings.yaml) | Yes; protected by `CODEOWNERS` |

Every event has `schema_version`, `seq`, `ts`, `run`, `actor`, `stage`, `type`, `data`, `prev_hash` and its own `hash` (SHA-256 of the event without `hash`).

## Development

```bash
uv run pytest                          # tests: the `orchestrate` command with a scripted agent and an in-memory GitHub
uv run ruff check . && uv run ruff format --check .
uv run mypy src tests                  # strict
```

CI runs the same three on every PR as the required check **"Orchestrator (lint, types, tests)"**. Tests drive only the `orchestrate` command (and, from #32, the policy check). They use a real git repository in a temporary directory and need no network or credentials.
