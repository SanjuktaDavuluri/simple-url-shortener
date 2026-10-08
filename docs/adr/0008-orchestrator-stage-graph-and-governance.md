---
status: accepted
date: 2026-10-07
---

# The orchestrator's stage graph, gates and human approval model

The orchestrator (ADR 0007) runs every request through one explicit stage graph. The graph is the workflow that delivered Release 1 by hand, made explicit and enforced: each stage has an **entry gate** (its prerequisites passed) and an **exit gate** (its output checked). A human approves at exactly the points where a human decided by hand.

```
request / Issue → ① INTAKE → ② REQUIREMENTS → ③ DESIGN → ④ DECOMPOSE
                                ⏸ approve spec   ⏸ approve ADRs  ⏸ approve tickets
   → fan-out, one lane per ticket, parallel where the "blocked by" graph allows:
       ⑤ IMPLEMENT (TDD, feature branch)  exit: ./mvnw verify green + policy checks
       ⑥ DOCUMENT                         exit: docs checks pass
       ⑦ PULL REQUEST                     exit: both required CI checks green
   → join (every lane) → ⑧ RELEASE READINESS  ⏸ human merges → ⑨ CLOSE-OUT (board, statuses, run report, metrics)
```

## Governance rules

- **Human approvals at four points:** the spec, each ADR, the ticket breakdown, and the merge. Everything else runs autonomously inside its guardrails.
- **Entry gates:** a stage starts only when every stage it depends on has passed its exit gate.
- **Bounded retries:** at most 2 per stage. The gate's failure output is fed back to the agent.
- **Fallback:** after the retries are used up, the run pauses and asks the engineer. It never loops.
- **Rollback per lane:** a failed ticket lane is undone (its branch deleted, its PR closed). Lanes that passed are kept.
- **Safe-stop:** `orchestrate stop` or a `stop` label on the run's Issue. The current step finishes, later steps are skipped, and the state is saved so the run can resume.
- **Parallelism follows the dependency graph:** independent tickets run as parallel lanes. A ticket starts only when its blockers are done, and release readiness waits for every lane.
- **Re-planning:** when an upstream artifact (spec, ADR, ticket) changes, every stage downstream of it is invalidated and re-run. Unaffected work is kept.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Approvals at spec, ADRs, tickets and merge** | Humans keep every high-impact decision they owned by hand; routine work is autonomous | The run waits for the engineer four times | **Chosen** |
| B | Approve every stage | Maximum control | Approval fatigue; little autonomy; slower than doing it by hand | Rejected |
| C | Approve only the merge | Fastest | Requirements and design decisions made without a human; wrong work discovered late | Rejected: too much autonomy for high-impact choices |
| D | **Retries capped at 2, then pause for a human** | Recovers from common agent mistakes; cost is bounded | Some fixable failures still need a human | **Chosen** |
| E | Unlimited or high retries | Fewer interruptions | Unbounded cost and time; loops on hopeless steps | Rejected |
| F | **Rollback per lane** | A failure costs only its own ticket | Lanes must stay independent (one branch each) | **Chosen** |
| G | Roll back the whole run | Simplest to reason about | Throws away work that passed | Rejected |
| H | **A linear pipeline instead of a graph** | Simpler | No parallel lanes, no joins, no targeted re-planning | Rejected |

**In short:** autonomy where a mistake is cheap and caught by a gate; a human where a decision is costly to reverse.

## Consequences

- Retry count, budgets and the policy list are settings in the orchestrator's spec, not code constants.
- Every gate result, retry, rollback, approval and re-plan is an audit event, and these events feed the delivery metrics.
- The four approval points match the ADR approval rule in `CLAUDE.md`, so practice and tooling don't diverge.
