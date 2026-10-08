"""The decompose Stage (spec 0002, stories 13-14).

The agent proposes vertical-slice tickets with blocking edges; once the breakdown is approved, the
tickets are published as Issues on the Release's milestone and the delivery board, blockers first.
"""

import json
from typing import Any, Literal

from orchestrator.agent import StepRequest
from orchestrator.approvals import gate_failed, record_gate
from orchestrator.context import RunContext
from orchestrator.gitops import commit_and_push, ensure_run_worktree, run_branch
from orchestrator.hashing import content_hash
from orchestrator.stages import roadmap_release
from orchestrator.state import RunState

STAGE = "decompose"
KINDS = ("Feature", "Fix", "Test", "Docs")

INSTRUCTIONS = """\
Break the approved spec into tracer-bullet tickets: each a narrow but complete vertical slice that
can be verified on its own and fits one session. Output tickets: a list of {key, title, what,
acceptance (list), blocked_by (keys), kind (Feature|Fix|Test|Docs)}. Use the vocabulary in
CONTEXT.md and respect the accepted ADRs."""

Ticket = dict[str, Any]


def order(tickets: list[Ticket]) -> tuple[list[Ticket], list[str]]:
    """Topological order, blockers first (ties by key), or the problems that prevent one."""
    problems: list[str] = []
    if not tickets:
        return [], ["Propose at least one ticket"]
    keys = [str(t.get("key", "")) for t in tickets]
    for key in sorted({k for k in keys if keys.count(k) > 1}):
        problems.append(f"Duplicate ticket key {key}")
    for t in tickets:
        key = t.get("key", "?")
        if not str(t.get("title", "")).strip() or not str(t.get("what", "")).strip():
            problems.append(f"{key} needs a title and what to build")
        if not t.get("acceptance"):
            problems.append(f"{key} needs acceptance criteria")
        if t.get("kind") not in KINDS:
            problems.append(f"{key}: kind must be one of {', '.join(KINDS)}")
        for blocker in t.get("blocked_by", []):
            if blocker not in keys:
                problems.append(f"{key} is blocked by {blocker}, which is not in the breakdown")
    if problems:
        return [], problems
    by_key = {t["key"]: t for t in tickets}
    remaining = {k: set(t.get("blocked_by", [])) for k, t in by_key.items()}
    ordered: list[Ticket] = []
    while remaining:
        ready = sorted(k for k, blockers in remaining.items() if not blockers)
        if not ready:
            return [], [f"The blocking edges form a cycle: {', '.join(sorted(remaining))}"]
        for k in ready:
            ordered.append(by_key[k])
            del remaining[k]
        for blockers in remaining.values():
            blockers.difference_update(ready)
    return ordered, []


def tickets_path(run: str) -> str:
    return f"delivery/runs/{run}/tickets.json"


class Begin:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        self.ctx.event("stage_started", STAGE)
        self.ctx.mirror("Stage **decompose** started: a ticket breakdown for approval.")
        return {"feedback": None, "attempts": 0}


class Draft:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        result = ctx.call_agent(
            StepRequest(
                run=ctx.run,
                stage=STAGE,
                instructions=INSTRUCTIONS,
                workspace=ensure_run_worktree(ctx.workspace, ctx.run),
                context={
                    "spec": state["spec"],
                    "adrs": state.get("adrs_accepted", []),
                    "feedback": state.get("feedback"),
                },
                budget_usd=ctx.settings.cost_cap_step_usd,
            )
        )
        return {"tickets": list(result.output.get("tickets", []))}


class Gate:
    """Checks the breakdown and writes it, in dependency order, to the Run's branch for review."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        ordered, problems = order(state.get("tickets", []))
        record_gate(ctx, STAGE, "ticket breakdown is complete", problems)
        if problems:
            return gate_failed(ctx, STAGE, "ticket breakdown", state, problems)
        worktree = ensure_run_worktree(ctx.workspace, ctx.run)
        path = tickets_path(ctx.run)
        text = json.dumps(ordered, indent=2, ensure_ascii=False) + "\n"
        (worktree / path).parent.mkdir(parents=True, exist_ok=True)
        (worktree / path).write_text(text)
        commit_and_push(
            worktree, ctx.run, [path], f"docs: ticket breakdown for #{ctx.issue} ({ctx.run})"
        )
        return {"tickets": ordered, "tickets_hash": content_hash(text), "feedback": None}


def describe_tickets(state: RunState) -> tuple[str, str, str, str]:
    rows = "\n".join(
        f"| {t['key']} | {t['title']} | {', '.join(t.get('blocked_by', [])) or '—'} |"
        for t in state["tickets"]
    )
    table = f"\n\n| Key | Ticket | Blocked by |\n|---|---|---|\n{rows}"
    return "tickets", tickets_path(state["run"]), state["tickets_hash"], table


class Decided:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        decision = state["decision"]
        if decision["approved"]:
            return {"feedback": None}
        return {"feedback": decision["reason"], "attempts": 0}


class Publish:
    """Creates the Issues, blockers first. Tickets already created are skipped: retries are safe."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        created = {
            e["data"]["key"]: e["data"]["issue"]
            for e in ctx.log.read()
            if e["type"] == "ticket_created"
        }
        release = roadmap_release(ctx.workspace, state.get("roadmap_item"))
        milestone = ctx.github.milestone_for_release(release) if release else None
        spec = state["spec"]
        spec_url = ctx.github.file_url(run_branch(ctx.run), spec["path"])
        for t in state["tickets"]:
            if t["key"] in created:
                continue
            blockers = [created[b] for b in t.get("blocked_by", [])]
            body = (
                f"Spec: [{spec['path']}]({spec_url}) · Run {ctx.run} (Issue #{ctx.issue})\n\n"
                f"## What to build\n\n{t['what']}\n\n## Acceptance criteria\n\n"
                + "\n".join(f"- [ ] {a}" for a in t["acceptance"])
                + "\n\n## Blocked by\n\n"
                + ("\n".join(f"- #{n}" for n in blockers) or "- None (can start immediately)")
                + "\n"
            )
            number = ctx.github.create_issue(t["title"], body, ("ready-for-agent",), milestone)
            for blocker in blockers:
                ctx.github.add_blocked_by(number, blocker)
            release_field = {"Release": release} if release else {}
            ctx.github.add_to_board(number, {"Status": "Todo", **release_field, "Kind": t["kind"]})
            ctx.event("ticket_created", STAGE, {"key": t["key"], "issue": number})
            created[t["key"]] = number
        issues = {t["key"]: created[t["key"]] for t in state["tickets"]}
        ctx.event("tickets_published", STAGE, {"issues": issues, "milestone": milestone})
        ctx.mirror("Tickets published: " + ", ".join(f"#{n}" for n in issues.values()))
        artifacts = [
            {
                "key": t["key"],
                "issue": issues[t["key"]],
                "hash": content_hash(json.dumps(t, sort_keys=True)),
            }
            for t in state["tickets"]
        ]
        ctx.event("stage_passed", STAGE, {"artifacts": {"tickets": artifacts}})
        return {"published": issues}


def after_gate(state: RunState) -> Literal["request_approval", "draft", "stop"]:
    if state.get("paused"):
        return "stop"
    return "draft" if state.get("feedback") else "request_approval"


def after_decided(state: RunState) -> Literal["publish", "draft"]:
    return "publish" if state["decision"]["approved"] else "draft"
