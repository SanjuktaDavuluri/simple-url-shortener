"""Approval Checkpoints and Exit Gate retries, shared by every Stage (ADR 0008)."""

from collections.abc import Callable
from pathlib import Path
from typing import Any

from langgraph.types import interrupt

from orchestrator.context import RunContext
from orchestrator.gitops import run_branch, uncommitted_changes
from orchestrator.policies import check_changes
from orchestrator.state import RunState

# (checkpoint, artifact path on the Run's branch, its content hash, extra text for the request)
Describe = Callable[[RunState], tuple[str, str, str, str]]
# Where the artifact lives when it isn't on the Run's documents branch: (branch, worktree, paths)
Location = Callable[[RunState], tuple[str, str, list[str]]]


class RequestApproval:
    """Posts the approval request. Kept apart from the waiting node, so it is never re-posted."""

    def __init__(
        self, ctx: RunContext, stage: str, describe: Describe, location: Location | None = None
    ) -> None:
        self.ctx, self.stage, self.describe, self.location = ctx, stage, describe, location

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        checkpoint, path, artifact_hash, extra = self.describe(state)
        branch, tree, paths = (
            self.location(state) if self.location else (run_branch(ctx.run), ctx.run, [path])
        )
        url = ctx.github.file_url(branch, path)
        comment = ctx.mirror(
            f"✅ Approval needed: **{checkpoint}**: [{path}]({url}) (hash `{artifact_hash[:12]}`)."
            f"{extra}\n\nApprove with `orchestrate approve {ctx.run} {checkpoint}` or a "
            f"maintainer's `approved:{checkpoint}` label; reject with "
            f'`orchestrate reject {ctx.run} {checkpoint} --reason "…"`.'
        )
        ctx.event(
            "approval_requested",
            self.stage,
            {"checkpoint": checkpoint, "path": path, "hash": artifact_hash, "url": url},
        )
        return {
            "approval": {
                "checkpoint": checkpoint,
                "path": path,
                "hash": artifact_hash,
                "requested_at": comment.created_at,
                "tree": tree,
                "paths": paths,
            }
        }


class AwaitApproval:
    """Waits for a human decision, then records it, bound to the submitted content hash."""

    def __init__(self, ctx: RunContext, stage: str) -> None:
        self.ctx, self.stage = ctx, stage

    def __call__(self, state: RunState) -> RunState:
        ctx, approval = self.ctx, state["approval"]
        checkpoint, artifact_hash = approval["checkpoint"], approval["hash"]
        decision: dict[str, Any] = interrupt({"kind": "approval", **approval})
        by, channel = decision["by"], decision["channel"]
        record = {"checkpoint": checkpoint, "by": by, "channel": channel, "hash": artifact_hash}
        if decision["decision"] == "approved":
            ctx.event("approved", self.stage, record, actor="engineer")
            ctx.mirror(f"**{checkpoint}** approved by @{by} ({channel}).")
            return {"decision": {"approved": True, "checkpoint": checkpoint}}
        reason = decision["reason"]
        ctx.event("rejected", self.stage, {**record, "reason": reason}, actor="engineer")
        ctx.mirror(f"**{checkpoint}** rejected by @{by}: {reason}\n\nRevising.")
        return {"decision": {"approved": False, "checkpoint": checkpoint, "reason": reason}}


def record_gate(ctx: RunContext, stage: str, gate: str, problems: list[str]) -> None:
    ctx.event("gate_result", stage, {"gate": gate, "passed": not problems, "problems": problems})


def gate_failed(
    ctx: RunContext,
    stage: str,
    what: str,
    state: RunState,
    problems: list[str],
    lane: str | None = None,
) -> RunState:
    """Feed the failure back for another attempt; once retries run out, fail the Stage and pause."""
    attempts = state.get("attempts", 0) + 1
    listing = "\n".join(f"- {p}" for p in problems)
    if attempts > ctx.settings.max_retries:
        ctx.event("stage_failed", stage, {"problems": problems, "lane": lane})
        ctx.event("paused", stage, {"reason": f"the {what} gate kept failing", "lane": lane})
        options = f"Resume to retry with `orchestrate resume {ctx.run}`"
        if lane:
            options += (
                f", or roll Lane {lane} back with "
                f'`orchestrate reject {ctx.run} lane:{lane} --reason "…"`'
            )
        ctx.mirror(
            f"⏸️ Paused: the {what} gate failed {attempts} times. Last problems:\n{listing}"
            f"\n\n{options}."
        )
        return {"attempts": attempts, "paused": True}
    ctx.event("retry", stage, {"gate": what, "attempt": attempts, "lane": lane})
    ctx.mirror(f"{what.capitalize()} gate failed (attempt {attempts}); revising:\n{listing}")
    return {"attempts": attempts, "feedback": "\n".join(problems)}


class Paused:
    """Waits for the engineer after a Stage failed for good: retry it, or roll back its Lane."""

    def __init__(
        self, ctx: RunContext, stage: str, lane_of: Callable[[RunState], str] | None = None
    ) -> None:
        self.ctx, self.stage, self.lane_of = ctx, stage, lane_of

    def __call__(self, state: RunState) -> RunState:
        lane = self.lane_of(state) if self.lane_of else None
        value: dict[str, Any] = interrupt({"kind": "paused", "stage": self.stage, "lane": lane})
        if value.get("action") == "rollback":
            return {
                "action": "rollback",
                "rollback_reason": value["reason"],
                "rollback_by": value["by"],
            }
        self.ctx.event("resumed", self.stage, {"lane": lane}, actor="engineer")
        return {"action": "retry", "paused": False, "attempts": 0}


def after_paused(state: RunState) -> str:
    return str(state.get("action", "retry"))


def review_step(
    ctx: RunContext, stage: str, tree: Path, dependencies_allowed: bool = False
) -> tuple[list[str], list[str]]:
    """After a step: policy problems in what it changed, and the dependency manifests it touched."""
    review = check_changes(ctx.policies, stage, uncommitted_changes(tree))
    problems = list(review.problems)
    if review.dependency_manifests and not dependencies_allowed:
        problems += [
            f"{m}: dependencies may only change while implementing a ticket"
            for m in review.dependency_manifests
        ]
    return problems, review.dependency_manifests
