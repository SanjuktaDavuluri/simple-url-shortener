---
status: accepted
date: 2026-10-07
---

# Re-planning through content-hash lineage: changed inputs invalidate only what depends on them

Plans change while a run is in progress (ADR 0008). An engineer edits an approved spec or ADR, new information arrives on the run's Issue, or implementation finds that the spec is ambiguous or wrong. The orchestrator must never build on stale inputs, must not throw away valid work, and must keep the trail of *which inputs each output came from*.

## Decisions

1. **Lineage by content hash.** Every artifact a stage produces (spec, ADRs, tickets, lane branches, PRs) is recorded with a content hash. Every stage records the hashes of the inputs it consumed and the approvals it relied on. That makes up the decision lineage: any output can be traced to its exact inputs and approvals (stored as audit events, ADR 0009).
2. **Automatic detection.** At every stage boundary, and on `orchestrate replan`, recorded input hashes are compared with the current ones. A mismatch **invalidates that stage and everything downstream of it** in the graph. Work that doesn't depend on the changed artifact is kept.
3. **Plan changes return to approval.** If re-planning changes the ticket breakdown (tickets added, removed or split), it goes back through the *approve tickets* checkpoint. A change confined to one ticket re-runs only that lane.
4. **Merged work is immutable.** If a change affects a ticket that has already merged, the orchestrator creates a **follow-up ticket**. It never reopens or rewrites merged history.
5. **Agents propose, humans approve.** When implementation finds a spec gap, the agent raises a **spec amendment** and the run pauses for approval. Approving it changes the spec's hash, which triggers the same re-plan. Agents never edit approved artifacts directly (ADR 0010).

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Content-hash lineage, automatic detection at every boundary, plus explicit `replan`** | Stale inputs are always caught and logged; only affected work re-runs; full input-to-output traceability | Hashing and dependency bookkeeping in the orchestrator | **Chosen** |
| B | Manual re-plan only (`orchestrate replan <stage>`) | Simpler | A silent edit leaves downstream work built on stale inputs, undetected | Rejected |
| C | Restart the whole run on any change | Simplest to reason about | Discards valid work and repeats approvals; slow and expensive | Rejected |
| D | Timestamps instead of content hashes | Cheap | A touched-but-unchanged file triggers needless re-runs, and timestamps prove nothing about content | Rejected |
| E | Allow re-planning to amend merged work | Fewer tickets | Rewrites reviewed history; breaks safe change management | Rejected: merged work is immutable |

**In short:** inputs are fingerprinted, a changed fingerprint invalidates exactly its dependents, and anything that changes the plan or touches approved artifacts goes back through a human.

## Consequences

- Stages must declare their inputs explicitly, so the graph can compute dependents. That declaration is part of each stage's definition and is tested.
- Re-plan events (what changed, the old and new hashes, what was invalidated) are first-class audit events and count in delivery metrics.
- Hashes cover normalised content (for example, trailing whitespace is ignored) so formatting-only edits don't trigger needless re-runs.
