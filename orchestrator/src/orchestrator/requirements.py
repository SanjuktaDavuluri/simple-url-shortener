"""The requirements Stage (spec 0002, stories 8-10, 21-26).

Clarifying questions are asked on the Issue one at a time; the spec is written on the Run's branch,
checked by its Exit Gate, then waits at the `spec` Approval Checkpoint.
"""

from typing import Any, Literal

from langgraph.types import interrupt

from orchestrator.agent import StepRequest
from orchestrator.context import RunContext
from orchestrator.gitops import commit_and_push, ensure_run_worktree, exists_on_main, run_branch
from orchestrator.hashing import content_hash
from orchestrator.spec_gate import check_spec
from orchestrator.state import RunState

STAGE = "requirements"

INSTRUCTIONS = """\
Turn Issue #{issue} into a spec. If anything material is unclear, ask ONE clarifying
question with your recommended answer (output: question, recommendation). When nothing
material is unclear, write a new spec at docs/specs/NNNN-<slug>.md using the spec template
in docs/specs/, linking roadmap item {roadmap}, and output its path (output: spec). Use the
vocabulary in CONTEXT.md and respect the ADRs in docs/adr/."""


class Begin:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        self.ctx.event("stage_started", STAGE)
        self.ctx.mirror("Stage **requirements** started: clarifying questions, then a spec.")
        return {"answers": [], "feedback": None, "attempts": 0}


class Draft:
    """One agent step: either the next clarifying question, or a (revised) spec."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        issue = ctx.github.get_issue(ctx.issue)
        worktree = ensure_run_worktree(ctx.workspace, ctx.run)
        result = ctx.call_agent(
            StepRequest(
                run=ctx.run,
                stage=STAGE,
                instructions=INSTRUCTIONS.format(
                    issue=issue.number, roadmap=state.get("roadmap_item")
                ),
                workspace=worktree,
                context={
                    "issue": {"number": issue.number, "title": issue.title, "body": issue.body},
                    "roadmap_item": state.get("roadmap_item"),
                    "answers": state.get("answers", []),
                    "feedback": state.get("feedback"),
                },
                budget_usd=ctx.settings.cost_cap_step_usd,
            )
        )
        if "question" in result.output:
            n = len(state.get("answers", [])) + 1
            question = str(result.output["question"])
            recommendation = str(result.output.get("recommendation", ""))
            comment = ctx.mirror(
                f"❓ **Q{n}**: {question}\n\n➡️ Recommended: {recommendation}\n\n"
                "Reply in a comment, then run `orchestrate resume " + ctx.run + "`."
            )
            ctx.event(
                "question_asked",
                STAGE,
                {
                    "n": n,
                    "question": question,
                    "recommendation": recommendation,
                    "comment": comment.id,
                },
            )
            return {
                "pending_question": {
                    "n": n,
                    "question": question,
                    "recommendation": recommendation,
                    "comment": comment.id,
                }
            }
        return {"spec_path": str(result.output["spec"]), "pending_question": None}


class AwaitAnswer:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        pending = state["pending_question"]
        assert pending is not None
        answer: dict[str, Any] = interrupt({"kind": "answer", **pending})
        self.ctx.event(
            "answer_received", STAGE, {"n": pending["n"], "by": answer["by"]}, actor="engineer"
        )
        answered = {
            "question": pending["question"],
            "recommendation": pending["recommendation"],
            "answer": answer["body"],
            "by": answer["by"],
        }
        return {"answers": [*state.get("answers", []), answered], "pending_question": None}


class Gate:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        path = state["spec_path"]
        worktree = ensure_run_worktree(ctx.workspace, ctx.run)
        file = worktree / path
        text = file.read_text() if file.is_file() else None
        problems = check_spec(
            path,
            text,
            exists_on_main=exists_on_main(ctx.workspace, path),
            roadmap_item=state.get("roadmap_item"),
        )
        ctx.event(
            "gate_result",
            STAGE,
            {"gate": "spec is complete", "passed": not problems, "problems": problems},
        )
        if not problems:
            assert text is not None
            commit_and_push(worktree, ctx.run, [path], f"docs: spec for #{ctx.issue} ({ctx.run})")
            return {"spec_hash": content_hash(text), "feedback": None}
        attempts = state.get("attempts", 0) + 1
        listing = "\n".join(f"- {p}" for p in problems)
        if attempts > ctx.settings.max_retries:
            ctx.event("stage_failed", STAGE, {"problems": problems})
            ctx.event("paused", STAGE, {"reason": "the spec gate kept failing"})
            ctx.mirror(
                f"⏸️ Paused: the spec gate failed {attempts} times. Last problems:\n{listing}"
            )
            return {"attempts": attempts, "paused": True}
        ctx.mirror(f"Spec gate failed (attempt {attempts}); revising:\n{listing}")
        return {"attempts": attempts, "feedback": "\n".join(problems)}


class RequestApproval:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        path, spec_hash = state["spec_path"], state["spec_hash"]
        url = ctx.github.file_url(run_branch(ctx.run), path)
        comment = ctx.mirror(
            f"✅ Approval needed: **spec** [{path}]({url}) (hash `{spec_hash[:12]}`).\n\n"
            f"Approve with `orchestrate approve {ctx.run} spec` or a maintainer's `approved:spec` "
            f'label; reject with `orchestrate reject {ctx.run} spec --reason "…"`.'
        )
        ctx.event(
            "approval_requested",
            STAGE,
            {"checkpoint": "spec", "path": path, "hash": spec_hash, "url": url},
        )
        return {"approval_requested_at": comment.created_at}


class AwaitApproval:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        path, spec_hash = state["spec_path"], state["spec_hash"]
        decision: dict[str, Any] = interrupt(
            {
                "kind": "approval",
                "checkpoint": "spec",
                "path": path,
                "hash": spec_hash,
                "requested_at": state["approval_requested_at"],
            }
        )
        if decision["decision"] == "approved":
            ctx.event(
                "approved",
                STAGE,
                {
                    "checkpoint": "spec",
                    "by": decision["by"],
                    "channel": decision["channel"],
                    "hash": spec_hash,
                },
                actor="engineer",
            )
            ctx.mirror(f"Spec approved by @{decision['by']} ({decision['channel']}).")
            ctx.event(
                "stage_passed", STAGE, {"artifacts": {"spec": {"path": path, "hash": spec_hash}}}
            )
            return {"spec_approved": True}
        ctx.event(
            "rejected",
            STAGE,
            {
                "checkpoint": "spec",
                "by": decision["by"],
                "channel": decision["channel"],
                "hash": spec_hash,
                "reason": decision["reason"],
            },
            actor="engineer",
        )
        ctx.mirror(f"Spec rejected by @{decision['by']}: {decision['reason']}\n\nRevising.")
        return {"feedback": decision["reason"], "attempts": 0, "spec_approved": False}


def after_draft(state: RunState) -> Literal["await_answer", "gate"]:
    return "await_answer" if state.get("pending_question") else "gate"


def after_gate(state: RunState) -> Literal["request_approval", "draft", "stop"]:
    if state.get("paused"):
        return "stop"
    return "draft" if state.get("feedback") else "request_approval"


def after_approval(state: RunState) -> Literal["draft", "done"]:
    return "done" if state.get("spec_approved") else "draft"
