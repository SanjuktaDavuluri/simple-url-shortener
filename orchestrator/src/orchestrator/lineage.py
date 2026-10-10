"""Re-plan through content-hash lineage (ADR 0011).

Each Stage declares the artifacts it consumes. When it passes (the Lanes Stage: when it starts), it
records their hashes and the approvals it relied on. At every Stage boundary, and on
`orchestrate replan`, the recorded hashes are compared with the artifacts as they are now. A change
invalidates the first Stage that consumes the artifact and every Stage after it; Stages upstream
are kept.

Approved artifacts live on the Run's documents branch until its documents PR merges, then on main.
"""

import json
from typing import Any

from orchestrator.context import RunContext
from orchestrator.gitops import (
    bring_in,
    ensure_run_worktree,
    fetch,
    run_branch,
    show,
    tickets_path,
)
from orchestrator.hashing import content_hash, spec_hash
from orchestrator.state import RunState

ORDER = ("requirements", "design", "decompose", "lanes")
# What each Stage consumes. The spec reaches a Lane through its ticket: decompose re-derives the
# tickets after any spec change, and only Lanes whose ticket changed are redone.
INPUTS = {
    "requirements": ("issue",),
    "design": ("spec",),
    "decompose": ("spec", "adrs"),
    "lanes": ("tickets",),
}
# Where a Re-plan restarts for each changed artifact. An edited breakdown is not redrafted: it
# goes straight back to the `tickets` Approval Checkpoint.
RESTART = {"issue": "requirements", "spec": "design", "adrs": "decompose", "tickets": "decompose"}

Change = dict[str, str]


class ReplanNeeded(Exception):
    """Raised at a Stage boundary when an input changed; the Run jumps to the `replan` node."""

    def __init__(self, changes: list[Change]) -> None:
        super().__init__(", ".join(c["artifact"] for c in changes))
        self.changes = changes


def ref(ctx: RunContext, state: RunState) -> str:
    """Where the approved artifacts are: main once the documents PR has merged on GitHub (whether
    or not the Run has noticed yet), otherwise the Run's documents branch."""
    docs = state.get("docs_pr")
    if state.get("docs_merged") or (docs and ctx.github.pr(docs).state == "merged"):
        return "origin/main"
    return f"origin/{run_branch(ctx.run)}"


def ticket_hash(ticket: dict[str, Any]) -> str:
    return content_hash(json.dumps(ticket, sort_keys=True))


def current(ctx: RunContext, state: RunState, artifact: str, at: str) -> str | None:
    """The artifact's hash as it is now on `at`, or None when it can't be read."""
    if artifact == "issue":
        issue = ctx.github.get_issue(ctx.issue)
        return content_hash(f"{issue.title}\n{issue.body}")
    if artifact == "spec":
        text = show(ctx.workspace, at, state["spec"]["path"])
        return None if text is None else spec_hash(text)
    if artifact == "adrs":
        parts = []
        for adr in state.get("adrs_accepted", []):
            text = show(ctx.workspace, at, adr["path"])
            parts.append(f"{adr['path']}:{content_hash(text or '')}")
        return content_hash("\n".join(parts))
    text = show(ctx.workspace, at, tickets_path(ctx.run))
    return None if text is None else content_hash(text)


def record(ctx: RunContext, state: RunState, stage: str, at: str | None = None) -> dict[str, str]:
    """The hashes of `stage`'s declared inputs, as they are now."""
    fetch(ctx.workspace)
    where = at or ref(ctx, state)
    hashes = {a: current(ctx, state, a, where) for a in INPUTS[stage]}
    return {a: h for a, h in hashes.items() if h is not None}


def approvals(ctx: RunContext, stage: str) -> list[dict[str, str]]:
    """The approvals given in `stage` since it last started, with the hashes they were bound to."""
    log = ctx.log.read()
    starts = [i for i, e in enumerate(log) if e["type"] == "stage_started" and e["stage"] == stage]
    since = starts[-1] if starts else 0
    return [
        {"checkpoint": e["data"]["checkpoint"], "hash": e["data"]["hash"]}
        for e in log[since:]
        if e["type"] == "approved" and e["stage"] == stage
    ]


def passed(
    ctx: RunContext, state: RunState, stage: str, data: dict[str, Any]
) -> dict[str, dict[str, str]]:
    """Record `stage` as passed with its inputs and approvals; returns the Run's updated lineage."""
    inputs = record(ctx, state, stage)
    ctx.event("stage_passed", stage, {**data, "inputs": inputs, "approvals": approvals(ctx, stage)})
    return {**state.get("lineage", {}), stage: inputs}


def changes(ctx: RunContext, state: RunState) -> list[Change]:
    """Every recorded input whose content has changed since it was recorded."""
    lineage = state.get("lineage", {})
    if not lineage:
        return []
    fetch(ctx.workspace)
    where = ref(ctx, state)
    found: dict[str, Change] = {}
    for stage in ORDER:
        for artifact, old in lineage.get(stage, {}).items():
            new = current(ctx, state, artifact, where)
            if new is not None and new != old and artifact not in found:
                found[artifact] = {"artifact": artifact, "old": old, "new": new}
    return list(found.values())


def check(ctx: RunContext, state: RunState) -> None:
    found = changes(ctx, state)
    if found:
        raise ReplanNeeded(found)


def restart(found: list[Change]) -> str:
    return min((RESTART[c["artifact"]] for c in found), key=ORDER.index)


class Replan:
    """Acts on the changes found at a boundary or by `orchestrate replan`: records them, invalidates
    the first Stage that consumes a changed artifact and every Stage after it, withdraws the
    approvals that were waiting, and brings the Run's branch up to date. The graph then continues
    from that Stage (`after_replan`); everything upstream is kept."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx, found = self.ctx, state["replan"]
        start = restart(found)
        recorded = state.get("lineage", {})
        stale = [s for s in ORDER[ORDER.index(start) :] if s in recorded]
        ctx.event("replanned", None, {"changes": found, "from": start, "invalidated": stale})
        for stage in stale:
            ctx.event("invalidated", stage, {"stage": stage, "reason": _what(found) + " changed"})
        self.withdraw_waiting_approvals(state)
        tree = ensure_run_worktree(ctx.workspace, ctx.run)
        message = f"docs: bring in the changes for a re-plan of {ctx.run} (#{ctx.issue})"
        bring_in(tree, ref(ctx, state), message)
        listing = ", ".join(
            f"{c['artifact']} (`{c['old'][:12]}` → `{c['new'][:12]}`)" for c in found
        )
        ctx.mirror(
            f"🔁 Re-plan: {listing} changed. Invalidated: {', '.join(stale) or 'nothing yet'}. "
            f"Continuing from **{start}**; everything before it is kept."
        )
        update: RunState = {
            "lineage": {s: h for s, h in recorded.items() if s not in stale},
            "feedback": None,
            "attempts": 0,
            "paused": False,
        }
        spec_change = next((c for c in found if c["artifact"] == "spec"), None)
        if spec_change and start != "requirements":
            update["spec"] = {**state["spec"], "hash": spec_change["new"]}
            update["spec_hash"] = spec_change["new"]
        if start == "decompose" and any(c["artifact"] == "tickets" for c in found):
            update["tickets"] = list(json.loads((tree / tickets_path(ctx.run)).read_text()))
            update["replan_target"] = "decompose_gate"
        else:
            update["replan_target"] = start
        docs = state.get("docs_pr")
        if docs and ctx.github.pr(docs).state == "closed":
            update["docs_prs"] = [n for n in state.get("docs_prs", []) if n != docs]
        return update

    def withdraw_waiting_approvals(self, state: RunState) -> None:
        """Approvals that were waiting are for work being redone. A Lane's merge approval stays
        until the Lanes are reconciled; an open documents PR is closed."""
        ctx = self.ctx
        waiting: dict[str, bool] = {}
        for e in ctx.log.read():
            if e["type"] == "approval_requested":
                waiting[e["data"]["checkpoint"]] = True
            elif e["type"] in ("approved", "rejected", "approval_withdrawn"):
                waiting[e["data"]["checkpoint"]] = False
        docs = state.get("docs_pr")
        if docs and not state.get("docs_merged") and ctx.github.pr(docs).state == "open":
            ctx.github.close_pr(docs)  # its documents are being redone; the Lanes Stage opens anew
        for checkpoint, open_ in waiting.items():
            if open_ and (not checkpoint.startswith("merge:") or checkpoint == f"merge:{docs}"):
                ctx.event("approval_withdrawn", None, {"checkpoint": checkpoint})


def _what(found: list[Change]) -> str:
    return ", ".join(c["artifact"] for c in found)


def after_replan(state: RunState) -> str:
    return str(state["replan_target"])
