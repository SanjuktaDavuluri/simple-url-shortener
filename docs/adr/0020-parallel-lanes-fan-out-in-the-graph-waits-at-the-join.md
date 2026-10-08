---
status: accepted
date: 2026-10-08
---

# Parallel Lanes: agent work fans out in the graph; human waits are held at the join

ADR 0008 decides that independent tickets run as parallel Lanes, that a ticket starts only when its blockers are done, and that release readiness waits for every Lane. This ADR decides *how* the Stage graph does that. It refines ADR 0008; it doesn't replace it.

A Lane does two kinds of thing:
- **Agent work**, which takes minutes: implement, verify, document, open the PR.
- **Waits**, which can take hours: required checks on the PR, a human merge, a dependency approval, or an engineer deciding about a paused Lane.

Both have to overlap across Lanes. The Run must also stay resumable from a single checkpoint, keep one hash-chained Event Log, and stay testable.

## Decision

1. **A scheduler node starts Lanes.** `lanes_schedule` starts every Lane whose blockers have all merged, in ticket order, while fewer than `max_parallel_lanes` Lanes are in flight. A Lane counts as in flight from its start until it merges or is rolled back. Lanes blocked by a rolled-back or skipped Lane are skipped.
2. **Agent work fans out in the graph.** The scheduler sends each Lane that has agent work to do to `lane_work` as a LangGraph `Send`. Those branches run concurrently in one superstep. A branch carries its Lane from its current phase to its next wait: checks on its PR, a dependency approval, or a pause.
3. **Branches never wait.** A branch never calls `interrupt`. It ends by recording its Lane's status in a reducer that merges Lanes by key, so concurrent branches never overwrite each other. A Safe-stop or a policy pause inside a branch is recorded on the Lane, not raised out of the branch.
4. **Waits are held after the join.** Every branch returns to `lanes_join`, the synchronization point, and then to the scheduler. The scheduler polls each Lane's PR (checks, merged, closed). It then routes to at most one human wait at a time, in this order:
   - a rollback
   - a paused Lane (`resume` retries it; `reject lane:<key>` rolls it back)
   - a dependency approval
   - one combined wait that lists every PR still waiting on checks or a merge

   So the CLI's single-interrupt `resume`, `approve` and `reject` work unchanged.
5. **Release readiness has one way in.** `lanes_done`, and after it release readiness, is reachable only from the scheduler, and only when every Lane is merged, rolled back or skipped.
6. **Shared resources are serialized.** One lock covers appends to the Event Log, keeping its hash chain intact. One lock covers git commands, because concurrent `fetch` and `worktree add` contend for the repository's ref locks. Agent calls and Exit Gate commands, which are the slow parts, run in parallel.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Fan-out of agent work with `Send`, waits held at the join (this decision)** | Parallel and synchronized paths are explicit in the graph. Agent work really runs concurrently, and waits overlap across Lanes. A freed slot is refilled at the next join. `resume` and approvals stay single-interrupt. | A larger refactor: per-Lane state moves into the Lane record. Two locks. Tests script the agent per Lane. | **Chosen** |
| B | A cooperative scheduler loop with no fan-out | Smallest change; deterministic | Parallelism is hidden in a loop, so the graph reads as linear. Agent work never overlaps. | Rejected: A gives real concurrency and an explicit join for a moderate extra cost |
| C | One subgraph per Lane, fanned out with `Send`, each waiting inside its own branch | The most literal "one branch per Lane" | Several interrupts pend at once, so `resume` must address each by id. A new Lane can't start until the whole batch joins. A Safe-stop raised in one branch fails the others mid-step. | Rejected: A keeps the explicit fan-out without these failure modes |
| D | Separate processes, one Run per ticket | Full isolation | No shared join or Event Log for the Run. Blocking edges and release readiness would need a second coordinator. | Rejected: it gives up the single traceable Run |

**In short:** what can run in parallel (agent work) fans out in the graph, and what has to wait for a human or CI is collected at one synchronization point. The Run stays concurrent, resumable and auditable.

## Consequences

- A Lane's progress is a status in its record: `pending`, `working` (with a phase), `paused`, `dependency`, `awaiting_checks`, `awaiting_merge`, `rolling_back`, `merged`, `rolled_back` or `skipped`. Retries, feedback and dependency approvals are per Lane.
- Events from concurrent Lanes interleave in the Event Log. Each Lane event carries its Lane key, so per-Lane timelines and metrics can still be computed.
- `resume` on a combined wait re-reads every waiting PR. `resume` on paused Lanes retries all of them. `reject lane:<key>` rolls back any paused Lane.
- Tests drive concurrency deterministically. A barrier proves that two Lanes run their agent steps at the same time, and the scripted agent answers per Lane.
