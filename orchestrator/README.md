# Delivery orchestrator

The **delivery plane** of this repository: a development-time command-line tool that drives a GitHub Issue through gated Stages to merge-ready pull requests, under human control. It never deploys, and it never connects to a running service (ADR 0007).

- **Spec:** [0002](../docs/specs/0002-delivery-orchestrator.md) · **Decisions:** ADRs [0007](../docs/adr/0007-delivery-orchestrator.md)–[0011](../docs/adr/0011-orchestrator-replanning-and-lineage.md) · **Vocabulary:** the Delivery section of [`CONTEXT.md`](../CONTEXT.md)
- **Status:** walking skeleton (#26). `start` runs the **intake** Stage; later Stages arrive with tickets #27–#35 (Release 2).

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
| `orchestrate start <issue>` | Starts a Run (`R-NNNN`) for the Issue and runs intake: records the Issue with content hashes and links the roadmap item it names. Refuses an Issue that already has an active Run |
| `orchestrate status [run]` | Without a Run: lists every Run. With one: its Stages, waiting approvals and cost so far |
| `orchestrate verify <run>` / `--all` | Checks that Event Logs are intact (sequence, each event's hash, the link to the previous event) and names the first broken event |

## Where things are kept

| What | Where | Committed? |
|---|---|---|
| Run state (LangGraph checkpoints) and in-progress Event Logs | `.orchestrator/` at the repository root | No (gitignored) |
| Finished Runs' Event Logs and reports | `delivery/runs/R-NNNN/` | Yes, through each Run's close-out PR (from #29) |
| Settings: retries, parallel Lanes, cost caps, model | [`settings.yaml`](settings.yaml) | Yes; protected by `CODEOWNERS` |

Every event has `schema_version`, `seq`, `ts`, `run`, `actor`, `stage`, `type`, `data`, `prev_hash` and its own `hash` (SHA-256 of the event without `hash`).

## Development

```bash
uv run pytest                          # tests: the `orchestrate` command with a scripted agent and an in-memory GitHub
uv run ruff check . && uv run ruff format --check .
uv run mypy src tests                  # strict
```

CI runs the same three on every PR as the required check **"Orchestrator (lint, types, tests)"**. Tests drive only the `orchestrate` command (and, from #32, the policy check). They use a real git repository in a temporary directory and need no network or credentials.
