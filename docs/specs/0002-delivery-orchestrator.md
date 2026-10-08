---
status: in-progress
date: 2026-10-07
release: 2
roadmap: R18
triage: ready-for-agent
---

# Spec 0002: Delivery orchestrator: drive a request from Issue to merge-ready pull requests, under human control

Glossary: `CONTEXT.md` (Delivery section) · Decisions: ADRs 0007–0011 · Two planes: `docs/architecture.md` · Deferred work: `docs/roadmap.md`

## Problem Statement

Delivery on this project follows a disciplined chain: roadmap item → spec → tickets → test-first branches → PRs → CI → human merge → board and docs updated. Today that chain is **practised by convention**. An engineer and Claude Code follow skills, the rules in `CLAUDE.md`, CI and branch protection. Nothing *runs* the chain, so nobody can:
- see where a request is in the chain
- prove that a gate wasn't skipped
- measure how delivery is going (success rate, retries, rollbacks, recovery time, latency, cost)
- recover cleanly when a plan changes halfway through

The evidence is spread across Issues, PRs and commits, with no single, tamper-evident record of who decided what, based on which inputs.

## Solution

A **delivery orchestrator**: a command-line tool in `orchestrator/` (Python, the Claude Agent SDK and LangGraph; ADR 0007). It takes one GitHub Issue and drives it through a fixed graph of **stages**, from requirements to merge-ready pull requests:
- Every stage has an **exit gate**.
- A human approves at four points: the spec, each ADR, the ticket breakdown, and every merge.
- Independent tickets run as **parallel lanes**.
- Failures are retried a bounded number of times, then the run **pauses** for the engineer. A failed lane is **rolled back** without disturbing the others.
- The engineer can **safe-stop** a run at any time and **resume** it later.
- When an approved input changes, the run **re-plans** only the affected work.

Every action is written to a **hash-chained event log**, committed to the repository with a readable **run report**. **Delivery metrics** are computed from all committed logs.

The orchestrator is development-time tooling. It never deploys, and it never connects to a running service; its validation uses temporary instances (ADR 0007, decision 6). The service gains nothing from it at runtime.

## User Stories

### Starting and following a run

1. As an engineer, I want to run `orchestrate start <issue>`, so that a GitHub Issue becomes a tracked delivery run with an ID (`R-NNNN`).
2. As an engineer, I want `start` to refuse an Issue that already has an active run, so that two runs never compete for the same work.
3. As an engineer, I want `orchestrate status [run]` to show the stage graph, each stage's state (pending, running, passed, failed, paused, invalidated, skipped), open approvals and cost so far, so that I know exactly where a run is.
4. As an engineer, I want the command to exit as soon as a run is waiting on a human, and `orchestrate resume <run>` to continue from the saved state, so that nothing has to stay running while I review.
5. As an engineer, I want every run to mirror its progress as comments on its Issue (stage started, gate result, question, approval needed, paused, finished), so that a reviewer can follow it on GitHub without the CLI.
6. As a reviewer, I want each comment to link to the artifact it is about (the spec file, ADR, tickets, PRs), so that I can check the work behind each step.

### Stages and gates

7. As an engineer, I want the **intake** stage to read the Issue, record its title, body and labels with their content hashes, and link it to its roadmap item where one is named, so that the run's starting point is fixed and traceable.
8. As an engineer, I want the **requirements** stage to ask its clarifying questions on the Issue, one at a time with a recommended answer, and wait for my replies, so that ambiguity is resolved before anything is written.
9. As an engineer, I want the requirements stage to produce a spec in `docs/specs/` using the spec template, so that requirements are recorded the same way as hand-written ones.
10. As an engineer, I want the requirements exit gate to fail a spec that is missing template sections, user stories, testing decisions or its roadmap link, so that incomplete specs never reach approval.
11. As an engineer, I want the **design** stage to propose ADRs only for decisions that are hard to reverse, surprising, and the result of a real trade-off, each listing every option with what it gains and costs, so that ADR quality matches the project's rule.
12. As an engineer, I want the design stage to be allowed to propose no ADR at all, recorded with its reason, so that trivial work doesn't produce ceremony.
13. As an engineer, I want the **decompose** stage to propose vertical-slice tickets with blocking edges and acceptance criteria, so that the work can run in parallel safely.
14. As an engineer, I want approved tickets published as GitHub Issues with the Release milestone, the board entry (Status, Release, Kind) and links back to the spec, so that the tracker matches the plan with no manual step.
15. As an engineer, I want each ticket to run as a **lane**: **implement** (test-first, on its own `feat/<issue>-<slug>` branch in its own git worktree) → **document** → **PR**, so that each ticket follows the same chain as before.
16. As an engineer, I want the implement gate to require `./mvnw verify` to pass in the lane's worktree, and the browser checks to pass against a temporary instance when the web page changed, so that no lane opens a PR that CI would reject.
17. As an engineer, I want the document stage to update the documents the ticket touched (spec status, the integration-testing plan's matrix, the README where behaviour changed), so that docs never fall behind the code.
18. As an engineer, I want the PR stage to open a PR that says `Closes #<ticket>`, uses conventional commits and links the run, and to pass only when every required CI check is green, so that traceability holds by construction.
19. As an engineer, I want the **release readiness** stage to start only after every lane's PR has been merged, and to check end-to-end traceability (each commit → ticket, each PR → Issue, each approval → approver, spec status updated), so that the result is complete before the run closes.
20. As an engineer, I want the **close-out** stage to write `report.md`, finish the event log, regenerate `delivery/metrics.md`, and open one PR containing the run's `delivery/runs/R-NNNN/` folder, so that the run's evidence is reviewed and merged like any other change.

### Human approvals

21. As an engineer, I want the run to pause at the four approval points (spec, each ADR, tickets, merge) and nowhere else, so that I control the decisions without babysitting routine steps.
22. As an engineer, I want to approve or reject with `orchestrate approve <run> <checkpoint>` / `orchestrate reject <run> <checkpoint> --reason "…"`, so that I can act from the terminal.
23. As an engineer, I want to approve on GitHub instead, with an `approved:spec`, `approved:tickets` or `approved:adr-NNNN` label applied by a maintainer, so that I can act from anywhere.
24. As an engineer, I want a rejection's reason fed back to the stage, which then produces a revised artifact for approval, so that a rejection improves the result instead of ending the run.
25. As an engineer, I want merge approval to mean that a human merged the PR on GitHub (the orchestrator never merges), so that branch protection and human review stay the final authority.
26. As a reviewer, I want every approval and rejection recorded with who made it, when, through which channel, and the content hash of what was approved, so that approvals can't be applied to a different version afterwards.
27. As an engineer, I want a high-impact change (any dependency added or upgraded) to add an extra approval checkpoint before its lane continues, so that supply-chain changes always get human review.

### Failure handling

28. As an engineer, I want a failed gate retried at most twice, with the gate's failure output given to the agent, so that small mistakes fix themselves without endless loops.
29. As an engineer, I want the run to pause and ask me once retries are used up, with the last failure output on the Issue, so that I decide what happens next.
30. As an engineer, I want a lane that fails for good to be **rolled back** (its PR closed, its branch and worktree deleted, its ticket returned to Ready) while lanes that passed are kept, so that one bad ticket doesn't cost the whole run.
31. As an engineer, I want `orchestrate stop <run>`, or a `stop` label on the run's Issue, to let the current step finish, skip the steps after it, save the state, and mark the run as stopped, so that I can halt work safely at any moment.
32. As an engineer, I want a stopped or paused run to continue with `orchestrate resume <run>` from exactly where it halted, so that no work is lost or repeated.
33. As an engineer, I want the run to safe-stop by itself when it reaches its cost cap (per agent step and per run), so that spend is bounded.

### Re-planning

34. As an engineer, I want every artifact (Issue, spec, ADR, ticket, lane branch, PR) recorded with a content hash, and every stage to record the hashes of the inputs and approvals it used, so that any output can be traced to its exact inputs.
35. As an engineer, I want a changed input detected at every stage boundary and on `orchestrate replan <run>`, invalidating only the stages downstream of it, so that stale work is redone and unaffected work is kept.
36. As an engineer, I want a re-plan that changes the ticket breakdown to return to the tickets approval, so that I approve every plan change.
37. As an engineer, I want a change that affects an already-merged ticket to create a follow-up ticket instead, so that merged history is never rewritten.
38. As an engineer, I want an agent that finds a gap in the spec during implementation to raise a **spec amendment** and pause for my approval rather than editing the spec, so that approved artifacts change only through approval.
39. As an engineer, I want whitespace-only edits to be ignored by the hashes, so that formatting changes don't trigger needless re-runs.

### Guardrails

40. As an engineer, I want every agent action (file write, shell command, network request) checked against `orchestrator/policies.yaml` before it runs, and blocked with a reason if it breaks a policy, so that prompts are never the only control.
41. As an engineer, I want every exit gate to check the actual result too (protected paths, credential patterns in the diff, dependency manifests, git and PR state), so that effects the pre-action check can't see are still caught.
42. As an engineer, I want a blocked action to be reported back to the agent so it can choose another way, and repeated blocks to count toward the retry limit, so that a block steers the work instead of crashing the run.
43. As a maintainer, I want the policy file, CI workflows, `CLAUDE.md` and committed `delivery/` logs to be out of the agent's reach, and changes to them made only by reviewed human PRs, so that the guardrails can't be loosened from inside a run.
44. As a maintainer, I want the orchestrator to refuse to push to `main`, force-push, merge PRs, delete branches other than its own lane branches, change repository settings, use `sudo`, or pipe downloads into a shell, so that change control holds even if an agent misbehaves.
45. As a maintainer, I want outbound network access limited to an allow-list (Maven Central, the npm registry, GitHub; ADR 0010), so that agent steps can't reach arbitrary hosts.
46. As a maintainer, I want any matched credential pattern to fail the gate without the matched text being written to logs, events or comments, so that secrets never leak into the record.
47. As a maintainer, I want the orchestrator never to connect to, restart or change a running service, including my local one on port 8000, and to use only temporary instances on their own port and data directory, so that the delivery plane can't disturb the product plane.

### Audit trail, report and metrics

48. As a reviewer, I want one append-only `events.jsonl` per run, in which each event carries `schema_version`, a sequence number, a timestamp, the actor (engineer, agent, CI), the stage, the event type, its details, the SHA-256 hash of the previous event and its own hash, so that the run's history is complete and tamper-evident.
49. As a reviewer, I want `orchestrate verify <run>` (or `--all`) to check every hash chain and report the first broken event, so that I can confirm nothing was edited or removed.
50. As a reviewer, I want each agent call recorded with its model, input and output tokens, cost and duration, so that spend is attributable to stages.
51. As a reviewer, I want `report.md` to show the stage graph with outcomes, a timeline, gate results, retries, rollbacks, approvals with approvers, re-plans and cost, so that a run can be understood without reading raw events.
52. As a maintainer, I want `orchestrate metrics` to regenerate `delivery/metrics.md` from every committed event log, with success rate, retry frequency, rollback frequency, MTTR, end-to-end latency (total and excluding time spent waiting for humans) and cost per run, so that delivery performance is measured, not asserted.
53. As a maintainer, I want local run state (checkpoints) kept in `.orchestrator/` (gitignored) and the committed record kept in `delivery/`, so that working data and evidence are separated (ADR 0009).

### Operating the tool

54. As a new engineer, I want `uv sync` and `uv run orchestrate --help` to be enough to start, with usage documented in `docs/onboarding.md` and `orchestrator/README.md`, so that the tool is self-explanatory.
55. As an engineer, I want the retry limit, cost caps, maximum parallel lanes and model in one settings file with sensible defaults, so that tuning doesn't require code changes.
56. As an engineer, I want the orchestrator to use my existing `gh` login and an `ANTHROPIC_API_KEY` from the environment, never stored in the repository, so that credentials stay with me.
57. As a maintainer, I want a live smoke run (`scripts/orchestrator-smoke.sh`) that drives a small, throwaway Issue end-to-end against the real Claude API and GitHub, so that the real adapters are proven without paying for it on every PR.

## Implementation Decisions

- **Module and toolchain.** `orchestrator/` is a uv-managed Python 3.13 project with a console entry point, `orchestrate`. LangGraph and the Claude Agent SDK are pinned in `uv.lock` (ADR 0007). Lint and format use ruff; types use mypy (strict). It is never packaged into the service's jar or image.
- **Stage graph.** `intake → requirements → design → decompose → lanes(implement → document → pr)* → release_readiness → close_out` is a LangGraph state graph. Lanes fan out according to the tickets' blocking edges, up to `max_parallel_lanes` at once, and join before release readiness. Each stage **declares its inputs** (which artifacts it consumes), so dependents can be computed for re-planning (ADR 0011).
- **Gates.** Each stage has an exit gate: a deterministic check that returns pass, or fail with output. Gates are code, not agent judgement. Examples: required spec sections are present, `./mvnw verify` exits 0, every required CI check is green, the policy diff checks pass, traceability is complete.
- **The Run's documents branch.** The requirements and design Stages write in a separate git worktree on `docs/run-R-NNNN`, branched from `origin/main` and pushed so approval requests can link to the files. Every orchestrator comment carries a hidden `<!-- orchestrator:R-NNNN -->` marker so its own comments are never read as answers, and label approvals count only when applied by a maintainer *after* the approval was requested.
- **ADRs and the ticket breakdown on the Run's branch.** Proposed ADRs are committed on `docs/run-R-NNNN`; approving `adr-NNNN` flips its frontmatter to `status: accepted` there, and an accepted ADR may not change afterwards (a new ADR supersedes it). The breakdown is written as `delivery/runs/R-NNNN/tickets.json` in dependency order, so the `tickets` approval binds to a file hash like every other checkpoint. Tickets go to the milestone of the roadmap item's Release and to the delivery board configured in settings.
- **The Run's documents reach `main` first.** The lanes Stage opens a PR for `docs/run-R-NNNN` (spec, ADRs, ticket breakdown) and waits for its merge, so every Lane branches from a `main` that already has the approved documents and its PR can link the spec on `main`.
- **Checks are per commit.** A Lane PR's gate reads the required checks of the commit the orchestrator pushed; a later push starts a new wait. A PR already merged counts as green, since branch protection merges only on green checks.
- **Exit Gate commands are settings** (`verify_command`, `web_paths`, `app_start_command`, `app_stop_command`, `browser_check_command`), defaulting to `./mvnw verify` and `scripts/local.sh` with `PORT`/`DATA_DIR`, so tests can substitute probes.
- **Policy enforcement in practice.** Every `StepRequest` carries a `guard` (the `PreToolUse` check). Blocks are `policy_blocked` events, and more than `max_retries` blocks in one step pauses the Run (in a Lane, that Lane), with `resume` retrying that step. Each Exit Gate reviews the step's uncommitted changes before anything is committed, so a violating change never reaches a branch. A dependency-manifest change is committed and pushed to the Lane branch so its `dependency:<lane>` approval can link the diff, and verification waits for that approval. Approvals of multi-file artifacts bind to the hash of all their files.
- **Failure handling in practice.** Every node first passes a boundary check: a Safe-stop request (written by `orchestrate stop` while another command holds the Run's lock) or a `stop` label halts the Run there, so the finished step is kept and `resume` continues from the next one. A Stage that runs out of retries waits in a paused node, where `resume` retries it and `reject <run> lane:<key>` rolls its Lane back; no failure ends the graph. Rolled-back Lanes skip the Lanes they block, independent Lanes continue, and the Run finishes *partially delivered*. A run-level cost cap can be raised on `resume` (`settings_changed` event).
- **Parallel Lanes in practice** (ADR 0020). A scheduler node starts each Lane whose blockers have all merged, in ticket order, while fewer than `max_parallel_lanes` are in flight (from the start until merged or rolled back). Lanes with agent work fan out as LangGraph `Send` branches that run concurrently and return to a join; a branch never waits for a human, and records a Safe-stop or a policy pause on its Lane instead of raising it. After the join, the scheduler reads each waiting PR and holds one wait at a time: a rollback, the paused Lanes (`resume` retries them all; `reject <run> lane:<key>` rolls one back), a dependency approval, or one combined wait listing every PR waiting on checks or a merge. Release readiness is reached only when every Lane has merged, been rolled back or been skipped. Event Log appends and git commands are serialized; agent steps and Exit Gates run in parallel. `lane_started` and `lane_finished` events bound each Lane's time in flight.
- **Re-planning in practice** (ADR 0011). Declared inputs: requirements ← the Issue; design ← the spec; decompose ← the spec and accepted ADRs; the Lanes ← the ticket breakdown. The spec reaches a Lane through its ticket: decompose re-derives the tickets after a spec change, and only Lanes whose ticket changed are redone. Approved artifacts are read from the Run's documents branch until its documents PR merges, then from `main`. A change found at a Stage boundary (or by `orchestrate replan`) jumps the Run to a `replan` node, dropping whatever it was waiting for; the node records the change, withdraws waiting approvals and continues from the first invalidated Stage. An edited breakdown returns to the `tickets` approval without a redraft. A spec amendment goes to its own branch, is approved as `amendment-N`, and reaches `main` as a PR; its merge is the spec change that re-plans the Run.
- **Bounded runs, not a daemon.** A command runs the graph until it completes, reaches a human checkpoint, pauses or stops, then exits. `resume` reloads the checkpoint and asks GitHub what changed: approvals, labels, merges, replies.
- **Approvals.** Checkpoints are `spec`, `adr-NNNN` (one per ADR), `tickets`, `merge:<pr>`, and `dependency:<lane>` (high impact). The channels are the CLI and maintainer-applied labels. Merge approval is detected only from the PR's merged state. An approval binds to the content hash of the artifact as it was when approved.
- **Two external adapters, each behind an interface:**
  - **Agent**: given a stage, instructions, a workspace path, allowed tools and a budget, it returns the files changed, structured output, and token, cost and duration figures. The real adapter uses the Claude Agent SDK (model `claude-opus-5-5`), with a `PreToolUse` hook that calls the policy check. The test adapter is a **scripted agent**: per stage, it applies scripted file edits and returns scripted output, including scripted failures.
  - **GitHub**: Issues, comments, labels, milestones, the board, PRs, check runs and merged state. The real adapter wraps the `gh` CLI. The test adapter is an **in-memory GitHub**.
  - Git itself is **not** faked. Tests use real git against a temporary repository with a local bare remote.
- **Policy check.** A pure function: `(policies, proposed action) → allowed | blocked(reason)`, where an action is a tool call or a diff. `policies.yaml` has a schema. The hook (before) and the gates (after) both use this same function (ADR 0010).
- **Event log.** Each event has `schema_version`, `seq`, `ts`, `run`, `actor`, `stage`, `type`, `data`, `prev_hash` and its own `hash` (SHA-256 of the event without `hash`; this also detects an edit to the last event), written as JSON lines. Event types: `run_started`, `stage_started`, `stage_passed`, `stage_failed`, `gate_result`, `retry`, `paused`, `resumed`, `approval_requested`, `approved`, `rejected`, `agent_call`, `policy_blocked`, `lane_rolled_back`, `replanned`, `invalidated`, `safe_stop`, `run_finished`; added while building: `lane_started`, `lane_finished`, `lane_skipped`, `approval_withdrawn`, `amendment_raised`, `amendment_pr_opened` and `follow_up_created`. Secrets are scanned before writing (ADR 0009).
- **Metrics definitions:**
  - **success rate:** runs that finished close-out ÷ runs that ended
  - **retry frequency:** retries ÷ stage executions
  - **rollback frequency:** rolled-back lanes ÷ lanes
  - **MTTR:** the mean time from a failed gate to the next passing gate of the same stage
  - **end-to-end latency:** from `run_started` to `run_finished`, reported in total and with time spent awaiting humans excluded
  - **cost per run:** the sum of the run's `agent_call` costs
- **Temporary service instances.** Validation that needs a running app builds it from the lane's worktree and starts it on a free port with a temporary data directory (`PORT` and `DATA_DIR`, as `scripts/local.sh` supports). The instance is always torn down afterwards.
- **Settings.** `orchestrator/settings.yaml`. Defaults:
  - `max_retries: 2`
  - `max_parallel_lanes: 2`
  - `cost_cap_step_usd: 5`
  - `cost_cap_run_usd: 50`
  - `model: claude-opus-5-5`
- **Repository layout.**
  - `orchestrator/`: code, tests, `policies.yaml`, `settings.yaml`, `README.md`
  - `delivery/runs/R-NNNN/`: `events.jsonl` and `report.md` (committed)
  - `delivery/metrics.md` (committed)
  - `.orchestrator/`: local state (gitignored)
- **CI.** A third job, **"Orchestrator (lint, types, tests)"**, runs `uv sync --locked`, `ruff`, `mypy` and `pytest`, and is **required on `main`**. It runs on **every** PR, not only on `orchestrator/` changes, because a path-filtered required check would leave unrelated PRs pending. The suite uses no network and no credentials.
- **Protected paths.** Branch protection is updated in the same PR that adds the job (`CLAUDE.md` rule). `CODEOWNERS` covers `orchestrator/policies.yaml`, `.github/` and `delivery/`.

## Testing Decisions

- **Good tests check what a user of the tool can observe:** the command's output and exit code, the run's status, `events.jsonl` (including a valid hash chain), `report.md` and `metrics.md`, the git branches and worktrees, and the in-memory GitHub's Issues, comments, labels and PRs. They never assert on LangGraph internals or private functions.
- **Seam 1, the `orchestrate` command (main seam).** Tests run real CLI commands against a temporary git repository with the **scripted agent** and **in-memory GitHub**. They cover:
  - the happy path: Issue → spec → approval → no ADR → tickets → two parallel lanes → merges → close-out
  - each approval channel, and rejection with revision
  - retry then pass; retries exhausted → paused → resume
  - lane rollback while the other lane is kept
  - safe-stop by command and by label, then resume
  - the cost cap triggering a safe-stop
  - re-plans: a spec edit invalidates only its dependents; a ticket-breakdown change returns to approval; a change to merged work creates a follow-up ticket; whitespace-only edits are ignored
  - spec amendments
  - tamper detection by `verify`
  - metrics values from known event logs
  - an approval bound to a stale hash being refused
  - the run never touching `main`
- **Seam 2, the policy check.** Table-driven tests: at least one *blocks* case and one *allows* case per rule in `policies.yaml`, plus disguised forms (for example, a `gh pr merge` alias, or `curl … | sh` split across arguments), and the rule that matched secrets never appear in the block reason.
- **Live smoke run (outside CI).** `scripts/orchestrator-smoke.sh` runs one real run on a throwaway Issue, using the real Agent SDK and `gh`. It is run on demand before the orchestrator is first used for real work, and after upgrades of the SDK or LangGraph. Results are recorded in that run's report.
- **Prior art:** the service's integration tests use the same approach. They drive the app through its HTTP interface with a scripted `ShortCodeGenerator` in place of randomness (spec 0001, `docs/plans/0001-integration-testing.md`). The scripted agent and in-memory GitHub play the same role here.

## Out of Scope

- Deploying anything, or any connection to a running service (ADR 0007, decision 6).
- A web UI or dashboard. `report.md` and `metrics.md` are the views.
- Running as a hosted service, webhook listener or scheduled bot. Runs are started on demand from the CLI.
- Merging PRs, or changing branch protection or repository settings.
- Multi-repository runs, and repositories other than this one.
- Learning or adapting the stage graph automatically. Changing the graph is a reviewed code change.
- Rewriting merged history (follow-up tickets instead).

## Further Notes

- Once this spec is implemented, the rest of Release 2 (R10 → R2 ∥ R12 → R11 → R21) is delivered **through** the orchestrator. Those runs become the first committed evidence in `delivery/`.
- The orchestrator's own tickets are delivered by hand, the same way as Release 1, because the tool doesn't exist yet.
- Spend on real runs is bounded by the cost caps and reported per run. The test suite costs nothing.
