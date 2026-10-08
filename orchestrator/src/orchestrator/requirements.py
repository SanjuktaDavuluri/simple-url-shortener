"""The requirements Stage (spec 0002, stories 8-10, 21-26).

Clarifying questions are asked on the Issue one at a time; the spec is written on the Run's branch,
checked by its Exit Gate, then waits at the `spec` Approval Checkpoint.
"""

from typing import Any, Literal

from langgraph.types import interrupt

from orchestrator.agent import StepRequest
from orchestrator.approvals import gate_failed, record_gate
from orchestrator.context import RunContext
from orchestrator.gitops import commit_and_push, ensure_run_worktree, exists_on_main
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
        result = ctx.call_agent(
            StepRequest(
                run=ctx.run,
                stage=STAGE,
                instructions=INSTRUCTIONS.format(
                    issue=issue.number, roadmap=state.get("roadmap_item")
                ),
                workspace=ensure_run_worktree(ctx.workspace, ctx.run),
                context={
                    "issue": {"number": issue.number, "title": issue.title, "body": issue.body},
                    "roadmap_item": state.get("roadmap_item"),
                    "answers": state.get("answers", []),
                    "feedback": state.get("feedback"),
                },
                budget_usd=ctx.settings.cost_cap_step_usd,
            )
        )
        if "question" not in result.output:
            return {"spec_path": str(result.output["spec"]), "pending_question": None}
        n = len(state.get("answers", [])) + 1
        question = str(result.output["question"])
        recommendation = str(result.output.get("recommendation", ""))
        comment = ctx.mirror(
            f"❓ **Q{n}**: {question}\n\n➡️ Recommended: {recommendation}\n\n"
            f"Reply in a comment, then run `orchestrate resume {ctx.run}`."
        )
        pending = {
            "n": n,
            "question": question,
            "recommendation": recommendation,
            "comment": comment.id,
        }
        ctx.event("question_asked", STAGE, pending)
        return {"pending_question": pending}


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
        record_gate(ctx, STAGE, "spec is complete", problems)
        if problems:
            return gate_failed(ctx, STAGE, "spec", state, problems)
        assert text is not None
        commit_and_push(worktree, ctx.run, [path], f"docs: spec for #{ctx.issue} ({ctx.run})")
        return {"spec_hash": content_hash(text), "feedback": None}


def describe_spec(state: RunState) -> tuple[str, str, str, str]:
    return "spec", state["spec_path"], state["spec_hash"], ""


class Decided:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        decision = state["decision"]
        if not decision["approved"]:
            return {"feedback": decision["reason"], "attempts": 0}
        spec = {"path": state["spec_path"], "hash": state["spec_hash"]}
        self.ctx.event("stage_passed", STAGE, {"artifacts": {"spec": spec}})
        return {"spec": spec, "feedback": None}


def after_draft(state: RunState) -> Literal["await_answer", "gate"]:
    return "await_answer" if state.get("pending_question") else "gate"


def after_gate(state: RunState) -> Literal["request_approval", "draft", "stop"]:
    if state.get("paused"):
        return "stop"
    return "draft" if state.get("feedback") else "request_approval"


def after_decided(state: RunState) -> Literal["draft", "next"]:
    return "next" if state["decision"]["approved"] else "draft"
