"""Approval Checkpoints and Exit Gate retries, shared by every Stage (ADR 0008)."""

from collections.abc import Callable
from typing import Any

from langgraph.types import interrupt

from orchestrator.context import RunContext
from orchestrator.gitops import run_branch
from orchestrator.state import RunState

# (checkpoint, artifact path on the Run's branch, its content hash, extra text for the request)
Describe = Callable[[RunState], tuple[str, str, str, str]]


class RequestApproval:
    """Posts the approval request. Kept apart from the waiting node, so it is never re-posted."""

    def __init__(self, ctx: RunContext, stage: str, describe: Describe) -> None:
        self.ctx, self.stage, self.describe = ctx, stage, describe

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        checkpoint, path, artifact_hash, extra = self.describe(state)
        url = ctx.github.file_url(run_branch(ctx.run), path)
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
    ctx: RunContext, stage: str, what: str, state: RunState, problems: list[str]
) -> RunState:
    """Feed the failure back for another attempt; once retries run out, fail the Stage and pause."""
    attempts = state.get("attempts", 0) + 1
    listing = "\n".join(f"- {p}" for p in problems)
    if attempts > ctx.settings.max_retries:
        ctx.event("stage_failed", stage, {"problems": problems})
        ctx.event("paused", stage, {"reason": f"the {what} gate kept failing"})
        ctx.mirror(f"⏸️ Paused: the {what} gate failed {attempts} times. Last problems:\n{listing}")
        return {"attempts": attempts, "paused": True}
    ctx.mirror(f"{what.capitalize()} gate failed (attempt {attempts}); revising:\n{listing}")
    return {"attempts": attempts, "feedback": "\n".join(problems)}
