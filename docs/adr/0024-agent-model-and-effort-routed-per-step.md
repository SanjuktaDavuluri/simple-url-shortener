---
status: accepted
date: 2026-10-08
---

# Agent model and effort are routed per agent step

ADR 0007 runs every agent step on the Claude Agent SDK. Until #109, every step used one model (`claude-opus-5-5`) at one effort (`high`). R-0001 cost $18.46. Most of that went on steps that don't need the strongest model: the documentation step mostly edits Markdown, and the implement step works against a spec and an Exit Gate that check it.

The steps aren't equal in how much judgement they need, or in what a weak answer costs:

| Step | What it decides | How a weak answer is caught |
|---|---|---|
| requirements | What is ambiguous, which questions to ask, the spec | Only by a human, at the `spec` approval. Everything downstream inherits it |
| design | ADR options and trade-offs | Only by a human, at each ADR approval |
| decompose | Vertical slices and blocking edges | The tickets gate (no cycles, acceptance criteria), then a human |
| implement | Code and tests for one ticket | `verify_command`, browser checks and CI, with bounded retries |
| document | Doc updates for one ticket | The diff check and review of the PR |

## Decision

Each agent step's model is chosen by the Run's settings: `stage_models` names a model for a step, and `model` covers the steps it doesn't name. One `effort` applies to every step. The defaults in `orchestrator/settings.yaml`:

- **Opus** for requirements and design: their output is checked only by a human, and every later step builds on it.
- **Sonnet** (the default `model`) for decompose and implement: machine gates catch most mistakes, and retries are bounded.
- **Haiku** for document.
- **Effort `medium`.**

Routing mistakes stop a Run before it starts, with `Refusing to start: …`, and no Event Log is created. That covers a step name that isn't an agent step, an effort the SDK doesn't know, and a model value that isn't a Claude model ID. `run_started` records the routing with the other settings, so every `agent_call` can be traced to the model that made it.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Route per step, with a default** | Strong models where a human is the only check; cheap models where gates check the work. One setting to change | A wrong model choice for a step lowers quality silently. That's mitigated by the gates, the approvals, and the routing being recorded per Run | **Chosen** |
| B | One model for everything (Opus, high effort) | Simplest. The best quality everywhere | Highest cost per Run, mostly spent on gated work. It was the reason for #109 | Rejected |
| C | One cheaper model for everything (Sonnet) | Cheaper still and simple | Weaker questions and ADRs, which only a human catches, and every later step inherits them | Rejected |
| D | Escalate the model on a retry (cheap first, strong after a failed gate) | Pays for the strong model only when needed | It works only for gated steps. It hides quality problems behind retries, and makes cost and behaviour harder to predict and audit | Rejected for now. Could be added for implement later |

## Consequences

- **Cost.** New Runs should cost noticeably less than R-0001. `delivery/metrics.md` (cost per Run) will show by how much.
- **Existing Runs keep the settings they started with** (#74), so R-0002 to R-0004 continue on Opus at high effort.
- **Model IDs are checked only for their form** (`claude-…`), not against the live model list. An unknown ID still fails at the first step that uses it, and pauses the Run with the SDK's message.
- **Changing a step's model is a settings change, not a code change.** It is recorded in `run_started`.
