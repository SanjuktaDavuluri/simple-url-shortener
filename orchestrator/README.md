# Delivery orchestrator

The **delivery plane** of this repository: a development-time command-line tool that drives a GitHub Issue through gated Stages to merge-ready pull requests, under human control. It never deploys, and it never connects to a running service (ADR 0007).

- **Spec:** [0002](../docs/specs/0002-delivery-orchestrator.md) · **Decisions:** ADRs [0007](../docs/adr/0007-delivery-orchestrator.md)–[0011](../docs/adr/0011-orchestrator-replanning-and-lineage.md) · **Vocabulary:** the Delivery section of [`CONTEXT.md`](../CONTEXT.md)
- **Status:** intake (#26), **requirements** with the spec approval (#27), **design** with one approval per ADR and **decompose** with the ticket approval and publishing (#28), **lanes → release readiness → close-out** for tickets one at a time (#29), and **policy guardrails** (#32). Parallel Lanes, failure handling, Re-plan, metrics and the real agent arrive with #30, #31, #33–#35 (Release 2).

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

| lanes | First a PR that takes the Run's documents to `main`; then, per ticket, `feat/<issue>-<slug>` in its own worktree: **implement** → **document** → **PR** (`Closes #<issue>`) | implement: `verify_command` passes in the worktree, plus browser checks against a **temporary instance** when web paths changed; PR: every required check on the pushed commit is green (a failure goes back to implement with the check output) | `merge:<pr>`: a human merges on GitHub; the orchestrator never merges |
| release readiness | — | Every PR merged; every Lane PR says `Closes #<issue>`; every commit references its ticket; every approval has an approver | — |
| close-out | `delivery/runs/R-NNNN/report.md` and `events.jsonl`, spec marked `implemented`, in one PR | — | that PR's merge |

A failing gate goes back to the agent with its problems, up to `max_retries` times; then the Stage fails and the Run pauses. A rejection goes back with its reason, and the revision needs a fresh approval.

**Temporary instances.** A check that needs a running app starts it from the Lane's worktree with `app_start_command` on a free port and a fresh `DATA_DIR`, runs `browser_check_command` against `BASE_URL`, then always runs `app_stop_command` and deletes the data directory. Your own service (for example on :8000) is never touched. The commands are settings, so tests replace them with probes.

## Policy guardrails

[`policies.yaml`](policies.yaml) holds the rules (ADR 0010). It's data, protected by `CODEOWNERS`, and agents may not edit it. A Run won't start without a valid policy file, and `run_started` records its hash. The same pure check enforces it in two places:

1. **Before every action.** Each agent step gets a `guard`, the `PreToolUse` hook for the real agent (#35). It checks every shell command, file write and fetch:
   - **git:** no pushes to `main`, no force-pushes, and no deleting branches other than the Run's own
   - **gh:** no `gh pr merge`, and no `gh api` calls that change protection, collaborators, hooks or secrets
   - **commands:** no `sudo`; no piping or running downloaded code; no `rm` outside the worktree
   - **files:** no writes to protected paths, no ADRs outside the design Stage, and no content that looks like a credential
   - **network:** hosts on the allow-list only

   Disguised forms are caught too: wrappers (`env`, `bash -c`, `eval`), absolute paths to the program, a backslash before it, and chained commands. A blocked action is recorded as `policy_blocked` and reported back to the agent, which can try another way. If more than `max_retries` actions are blocked in one step, the Run **pauses**, and `orchestrate resume` retries that step.
2. **After every step.** Each Exit Gate reviews what the step actually changed: protected and Stage-only paths, and credentials in added lines. The reason names the file, line and pattern, **never the matched text**. A **dependency manifest** change (`pom.xml`, `e2e/package*.json`, the orchestrator's `pyproject.toml`/`uv.lock`, the Maven wrapper) adds an Approval Checkpoint, `dependency:<lane>`, before the Lane's verify gate.

## Where things are kept

| What | Where | Committed? |
|---|---|---|
| Run state (LangGraph checkpoints), in-progress Event Logs and Run worktrees | `.orchestrator/` at the repository root | No (gitignored) |
| Finished Runs' Event Logs and reports | `delivery/runs/R-NNNN/` | Yes, through each Run's close-out PR (from #29) |
| Settings: retries, parallel Lanes, cost caps, model, delivery board, Exit Gate commands | [`settings.yaml`](settings.yaml) | Yes; protected by `CODEOWNERS` |

Every event has `schema_version`, `seq`, `ts`, `run`, `actor`, `stage`, `type`, `data`, `prev_hash` and its own `hash` (SHA-256 of the event without `hash`).

## Development

```bash
uv run pytest                          # tests: the `orchestrate` command with a scripted agent and an in-memory GitHub
uv run ruff check . && uv run ruff format --check .
uv run mypy src tests                  # strict
```

CI runs the same three on every PR as the required check **"Orchestrator (lint, types, tests)"**. Tests drive only the `orchestrate` command (and, from #32, the policy check). They use a real git repository in a temporary directory and need no network or credentials.
