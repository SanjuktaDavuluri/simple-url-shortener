---
status: accepted
date: 2026-10-07
---

# Orchestrator policy guardrails, enforced before every action and verified after every step

Agent steps (ADR 0007) act on the repository with real tools: file edits, shell commands, git and the GitHub CLI. Prompts are not a control. We define **policies an agent step cannot break**, keep them in a **versioned, protected file** (`orchestrator/policies.yaml`), and enforce them in **two layers**.

## The policies

| Category | Rule |
|---|---|
| **Change control: git** | Never push to `main`, never force-push, never merge a PR (humans merge), and only delete the run's own lane branches |
| **Change control: protected paths** | No agent edits to `.github/workflows/`, branch protection settings, `CLAUDE.md`, `orchestrator/policies.*` or the committed `delivery/` audit logs. ADR files may be written only in the DESIGN stage, and become `accepted` only after human approval (ADR 0008) |
| **Change control: dependencies** | Adding or upgrading any dependency (`pom.xml`, `e2e/package.json`, `orchestrator/` manifests) is high-impact and triggers an extra human approval checkpoint |
| **Security: commands** | Blocked: piping downloads into a shell, `sudo`, deleting outside the workspace, `gh pr merge`, and `gh api` calls that change repository settings or protection |
| **Security: network** | Outbound access only to an allow-list: Maven Central, the npm registry, GitHub |
| **Security: secrets** | Before any commit or PR, the diff is scanned for credential patterns (API keys, tokens, private keys). A match fails the gate, and the matched content is never written to logs or events |
| **Cost** | A dollar cap per agent step and per run. Reaching a cap triggers a safe-stop (ADR 0008) |
| **Compliance and traceability** | Every commit references its ticket, every PR closes its Issue, and every approval is recorded with its approver. Missing links fail the release-readiness gate |

## Enforcement

1. **Before the action (prevent):** Claude Agent SDK `PreToolUse` hooks check every tool call (file write, shell command, network request) against the policies and **block** violations before they run. Each block is an audit event (ADR 0009).
2. **After the step (verify):** each exit gate checks the actual result: the diff for protected paths and secrets, the dependency manifests, and the git and PR state for traceability. This catches anything the hooks could not see, such as effects of an allowed command.

Policies are data, not code. Changing a guardrail is a reviewed pull request by a human, and the policy file itself is protected from agent edits.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Both layers: hooks before, gates after; policies in a protected, versioned file** | Harm prevented *and* results proven; each layer covers the other's blind spot; guardrail changes are reviewed | Two enforcement points to keep consistent (both read the same policy file) | **Chosen** |
| B | Hooks only | One mechanism | Nothing verifies the actual outcome; indirect effects of allowed commands go unseen | Rejected |
| C | Exit gates only | Simple and outcome-based | Harmful actions (a force-push, a leaked secret in a command) have already happened when caught | Rejected |
| D | Policies in prompts / `CLAUDE.md` only | No code | Advisory, not enforced; an agent can ignore or misread them | Rejected as a control. Prompts may *explain* policies, never *enforce* them |
| E | Policies hard-coded in the orchestrator | Simple | Changing a guardrail means a code change mixed with logic; harder to review what changed | Rejected |

**In short:** guardrails are enforced, not requested. They are checked before the action and proven after it, and they can only be changed by a reviewed human change.

## Consequences

- The policy file has a schema and its own unit tests. Each rule has at least one test proving it blocks and one proving legitimate work passes.
- A blocked action is not a failure of the run. The agent is told why it was blocked and can choose another way. Repeated blocks count toward the retry limit (ADR 0008).
- The network allow-list will need extending when a new legitimate source appears. That is a reviewed policy change, by design.
