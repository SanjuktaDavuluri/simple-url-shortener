---
status: accepted
date: 2026-10-07
---

# URL validation rules live in a separate `rules` package, not inline in the create-link flow

Every check on a submitted long URL is a **rule**. A rule is a small, independent unit that takes a URL and returns *pass* or a *rejection reason*. Rules live in their own `rules` package (behind a `Rule` interface), and a **rule set** lists the rules that are active. The create-link flow makes one call, `rules.check(url)`, and knows nothing about individual rules. Each rule has its own tests, and a new rule is added as a new unit plus an entry in the rule set, without touching the core flow.

The v1 Rule Set, in order: the URL must be well-formed, its scheme must be `http`/`https`, it must have a host, it must be at most 2048 characters, and it must not point back to the shortener's own domain (a Self-link). The well-formed Rule was added in #16 as a new class plus one Rule Set entry, with no change to the create-link flow. It was the first use of the extension path this ADR sets up.

## Why a seam here at all

The obvious path is a few `if` statements inside the create-link handler. We chose against that deliberately, because the URL rules are the part of the system we **expect to change most**. Safeguards will be added over time, starting with roadmap item R6, which blocks private and internal addresses. We want them to evolve, be tested and be reviewed independently of the core create/redirect pipeline. **Do not "simplify" the rules back into the handler.**

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| 0 | **Inline checks** in the create-link handler | Least code; obvious at first glance | Rules are tangled with the pipeline; you can't test one rule alone; every new safeguard edits core code | Rejected: it fails the "own lifecycle" requirement |
| A | **Separate module in the same repo** (rule interface + rule set) | Each rule is tested alone; new rules don't touch the core flow; same repo, same PR, same CI | A small amount of indirection (an interface and a rule set) for just three rules today | **Chosen**: it is the smallest structure that gives rules their own lifecycle |
| B | **A plus rules enabled and configured from a file** (e.g. `rules.yaml`) | Rules can be switched or tuned without a code change or deploy | Config parsing and validation; a config bug can silently disable a safeguard; nobody needs it yet | Deferred: roadmap R9, to be adopted only if that need appears |
| C | **Separately published library (its own Maven artifact) or service** with its own repo, versions and releases | A truly independent lifecycle and ownership | Two repos, version pinning, release overhead, and for a service a network hop on every link creation. All of that for three rules | Rejected as overkill for a single-team, single-node project |

**In short:** we chose **A over 0** to get a seam where we expect change. We chose **A over B** because we have no need yet to change rules without a deploy. We chose **A over C** because the independence we need is in testing and evolution, not in deployment or ownership.

## Consequences

- Adding a rule means a new rule unit, its tests and a rule-set entry. The core flow and its tests stay unchanged. That is the evidence that the seam works.
- Rules must be pure checks on the URL. If a future rule needs I/O, such as R6's DNS lookup, it should still fit the interface. If it doesn't, the interface needs a new ADR rather than a special case in the handler.
