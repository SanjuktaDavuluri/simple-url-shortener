"""Builds the Stage graph and runs it: until it finishes, waits on a human, or pauses (ADR 0008)."""

import os
import sqlite3
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Any, Literal

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command

from orchestrator import decompose, design, lanes, requirements
from orchestrator.approvals import AwaitApproval, Paused, RequestApproval, after_paused
from orchestrator.closeout import CloseOut, ReleaseReadiness
from orchestrator.context import RunContext, RunPaused, RunStopped
from orchestrator.stages import STAGES, Intake
from orchestrator.state import RunState

Graph = CompiledStateGraph[RunState, None, RunState, RunState]


def _after_intake(state: RunState) -> Literal["next", "stop"]:
    return "next" if state.get("intake_passed") else "stop"


class Boundary:
    """Runs before every node: a requested Safe-stop or a `stop` label halts the Run here, so the
    step that just finished is kept and the next one waits for `orchestrate resume`."""

    def __init__(self, ctx: RunContext, name: str, node: Callable[[RunState], RunState]) -> None:
        self.ctx, self.name, self.node = ctx, name, node

    def __call__(self, state: RunState) -> RunState:
        stage = next((s for s in STAGES if self.name.startswith(s)), self.name)
        self.ctx.check_boundary(stage)
        return self.node(state)


def build(ctx: RunContext, checkpointer: SqliteSaver) -> Graph:
    g = StateGraph(RunState)

    def node(name: str, action: Callable[[RunState], RunState]) -> None:
        g.add_node(name, Boundary(ctx, name, action))

    def edges(source: str, route: Callable[[RunState], str], targets: dict[str, str]) -> None:
        g.add_conditional_edges(source, route, targets)  # type: ignore[arg-type]

    always_retry: Callable[[RunState], str] = lambda state: "retry"  # noqa: E731

    node("intake", Intake(ctx))
    g.add_edge(START, "intake")
    edges("intake", _after_intake, {"next": "requirements", "stop": END})

    # requirements: questions, spec, spec gate, spec approval
    node("requirements", requirements.Begin(ctx))
    node("requirements_draft", requirements.Draft(ctx))
    node("requirements_await_answer", requirements.AwaitAnswer(ctx))
    node("requirements_gate", requirements.Gate(ctx))
    node("requirements_paused", Paused(ctx, requirements.STAGE))
    node(
        "requirements_request_approval",
        RequestApproval(ctx, requirements.STAGE, requirements.describe_spec),
    )
    node("requirements_await_approval", AwaitApproval(ctx, requirements.STAGE))
    node("requirements_decided", requirements.Decided(ctx))
    g.add_edge("requirements", "requirements_draft")
    edges(
        "requirements_draft",
        requirements.after_draft,
        {"await_answer": "requirements_await_answer", "gate": "requirements_gate"},
    )
    g.add_edge("requirements_await_answer", "requirements_draft")
    edges(
        "requirements_gate",
        requirements.after_gate,
        {
            "request_approval": "requirements_request_approval",
            "draft": "requirements_draft",
            "stop": "requirements_paused",
        },
    )
    edges("requirements_paused", always_retry, {"retry": "requirements_draft"})
    g.add_edge("requirements_request_approval", "requirements_await_approval")
    g.add_edge("requirements_await_approval", "requirements_decided")
    edges(
        "requirements_decided",
        requirements.after_decided,
        {"draft": "requirements_draft", "next": "design"},
    )

    # design: ADRs (or none), each approved separately
    node("design", design.Begin(ctx))
    node("design_draft", design.Draft(ctx))
    node("design_gate", design.Gate(ctx))
    node("design_paused", Paused(ctx, design.STAGE))
    node("design_request_approval", RequestApproval(ctx, design.STAGE, design.DescribeAdr(ctx)))
    node("design_await_approval", AwaitApproval(ctx, design.STAGE))
    node("design_decided", design.Decided(ctx))
    node("design_done", design.Done(ctx))
    g.add_edge("design", "design_draft")
    g.add_edge("design_draft", "design_gate")
    edges(
        "design_gate",
        design.after_gate,
        {
            "request_approval": "design_request_approval",
            "draft": "design_draft",
            "done": "design_done",
            "stop": "design_paused",
        },
    )
    edges("design_paused", always_retry, {"retry": "design_draft"})
    g.add_edge("design_request_approval", "design_await_approval")
    g.add_edge("design_await_approval", "design_decided")
    edges(
        "design_decided",
        design.after_decided,
        {
            "request_approval": "design_request_approval",
            "draft": "design_draft",
            "done": "design_done",
        },
    )
    g.add_edge("design_done", "decompose")

    # decompose: ticket breakdown, approval, publish
    node("decompose", decompose.Begin(ctx))
    node("decompose_draft", decompose.Draft(ctx))
    node("decompose_gate", decompose.Gate(ctx))
    node("decompose_paused", Paused(ctx, decompose.STAGE))
    node(
        "decompose_request_approval",
        RequestApproval(ctx, decompose.STAGE, decompose.describe_tickets),
    )
    node("decompose_await_approval", AwaitApproval(ctx, decompose.STAGE))
    node("decompose_decided", decompose.Decided(ctx))
    node("decompose_publish", decompose.Publish(ctx))
    g.add_edge("decompose", "decompose_draft")
    g.add_edge("decompose_draft", "decompose_gate")
    edges(
        "decompose_gate",
        decompose.after_gate,
        {
            "request_approval": "decompose_request_approval",
            "draft": "decompose_draft",
            "stop": "decompose_paused",
        },
    )
    edges("decompose_paused", always_retry, {"retry": "decompose_draft"})
    g.add_edge("decompose_request_approval", "decompose_await_approval")
    g.add_edge("decompose_await_approval", "decompose_decided")
    edges(
        "decompose_decided",
        decompose.after_decided,
        {"publish": "decompose_publish", "draft": "decompose_draft"},
    )
    g.add_edge("decompose_publish", "lanes")

    # lanes: the Run's documents reach main first, then each ticket in dependency order
    node("lanes", lanes.Begin(ctx))
    node("docs_checks", lanes.AwaitChecks(ctx, lanes.docs_pr, lanes.docs_sha))
    node("docs_checks_failed", lanes.DocsChecksFailed(ctx))
    node("docs_paused", Paused(ctx, lanes.STAGE))
    node("docs_request_merge", lanes.RequestMerge(ctx, lanes.docs_pr))
    node("docs_await_merge", lanes.AwaitMerge(ctx, lanes.docs_pr))
    node("lane_begin", lanes.LaneBegin(ctx))
    node("implement", lanes.Implement(ctx))
    node(
        "dependency_request_approval",
        RequestApproval(ctx, "implement", lanes.describe_dependency, lanes.dependency_location),
    )
    node("dependency_await_approval", AwaitApproval(ctx, "implement"))
    node("dependency_decided", lanes.DependencyDecided(ctx))
    node("verify", lanes.Verify(ctx))
    node("lane_paused", Paused(ctx, "implement", lanes.lane_key))
    node("document", lanes.Document(ctx))
    node("document_paused", Paused(ctx, "document", lanes.lane_key))
    node("open_pr", lanes.OpenPr(ctx))
    node("lane_checks", lanes.AwaitChecks(ctx, lanes.lane_pr, lanes.lane_sha))
    node("ci_failed", lanes.CiFailed(ctx))
    node("lane_request_merge", lanes.RequestMerge(ctx, lanes.lane_pr))
    node("lane_await_merge", lanes.AwaitMerge(ctx, lanes.lane_pr))
    node("lane_merged", lanes.LaneMerged(ctx))
    node("rollback", lanes.Rollback(ctx))
    node("lanes_done", lanes.LanesDone(ctx))
    g.add_edge("lanes", "docs_checks")
    edges(
        "docs_checks",
        lanes.after_docs_checks,
        {
            "recheck": "docs_checks",
            "request_merge": "docs_request_merge",
            "stop": "docs_checks_failed",
        },
    )
    g.add_edge("docs_checks_failed", "docs_paused")
    edges("docs_paused", always_retry, {"retry": "docs_checks"})
    g.add_edge("docs_request_merge", "docs_await_merge")
    edges(
        "docs_await_merge",
        lanes.after_merge,
        {"recheck": "docs_await_merge", "next": "lane_begin", "closed": "docs_checks_failed"},
    )
    edges(
        "lane_begin",
        lanes.after_lane_begin,
        {"implement": "implement", "next_lane": "lane_begin", "lanes_done": "lanes_done"},
    )
    edges(
        "implement",
        lanes.after_implement,
        {
            "verify": "verify",
            "dependency": "dependency_request_approval",
            "implement": "implement",
            "stop": "lane_paused",
        },
    )
    g.add_edge("dependency_request_approval", "dependency_await_approval")
    g.add_edge("dependency_await_approval", "dependency_decided")
    edges(
        "dependency_decided",
        lanes.after_dependency,
        {"verify": "verify", "implement": "implement"},
    )
    edges(
        "verify",
        lanes.after_verify,
        {"document": "document", "implement": "implement", "stop": "lane_paused"},
    )
    edges("lane_paused", after_paused, {"retry": "implement", "rollback": "rollback"})
    edges(
        "document",
        lanes.after_document,
        {"open_pr": "open_pr", "document": "document", "stop": "document_paused"},
    )
    edges("document_paused", after_paused, {"retry": "document", "rollback": "rollback"})
    g.add_edge("open_pr", "lane_checks")
    edges(
        "lane_checks",
        lanes.after_lane_checks,
        {"recheck": "lane_checks", "request_merge": "lane_request_merge", "failed": "ci_failed"},
    )
    edges("ci_failed", lanes.after_ci_failed, {"implement": "implement", "stop": "lane_paused"})
    g.add_edge("lane_request_merge", "lane_await_merge")
    edges(
        "lane_await_merge",
        lanes.after_merge,
        {"recheck": "lane_await_merge", "next": "lane_merged", "closed": "rollback"},
    )
    edges("lane_merged", lanes.after_lane, {"next_lane": "lane_begin", "lanes_done": "lanes_done"})
    edges("rollback", lanes.after_lane, {"next_lane": "lane_begin", "lanes_done": "lanes_done"})
    g.add_edge("lanes_done", "release_readiness")

    # release readiness and close-out
    node("release_readiness", ReleaseReadiness(ctx))
    node("readiness_paused", Paused(ctx, "release_readiness"))
    node("close_out", CloseOut(ctx))
    edges(
        "release_readiness",
        lambda state: "stop" if state.get("paused") else "close_out",
        {"stop": "readiness_paused", "close_out": "close_out"},
    )
    edges("readiness_paused", always_retry, {"retry": "release_readiness"})
    g.add_edge("close_out", END)
    return g.compile(checkpointer=checkpointer)


@contextmanager
def open_graph(ctx: RunContext) -> Iterator[Graph]:
    ctx.workspace.state_dir.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(ctx.workspace.checkpoint_db, check_same_thread=False) as conn:
        yield build(ctx, SqliteSaver(conn))


def _config(ctx: RunContext) -> Any:
    return {"configurable": {"thread_id": ctx.run}, "recursion_limit": 10_000}


def _invoke(ctx: RunContext, value: Any) -> None:
    lock = ctx.workspace.lock(ctx.run)
    lock.parent.mkdir(parents=True, exist_ok=True)
    lock.write_text(str(os.getpid()))
    try:
        with open_graph(ctx) as graph:
            graph.invoke(value, _config(ctx))
    except RunStopped as stopped:
        ctx.event("safe_stop", stopped.stage or None, {"reason": stopped.reason})
        ctx.mirror(
            f"⏹️ Stopped: {stopped.reason}. The finished step is kept; continue with "
            f"`orchestrate resume {ctx.run}`."
        )
    except RunPaused as paused:
        ctx.event("paused", paused.stage, {"reason": paused.reason})
        ctx.mirror(
            f"⏸️ Paused in **{paused.stage}**: {paused.reason}. "
            f"Resume with `orchestrate resume {ctx.run}`."
        )
    finally:
        lock.unlink(missing_ok=True)


def start(ctx: RunContext) -> None:
    _invoke(ctx, {"run": ctx.run, "issue": ctx.issue})


def pending_step(ctx: RunContext) -> bool:
    """True when the Run stopped mid-step (a Safe-stop) and can continue from there."""
    with open_graph(ctx) as graph:
        snapshot = graph.get_state(_config(ctx))
    return bool(snapshot.next) and not snapshot.interrupts


def continue_run(ctx: RunContext) -> None:
    """Continue a Run that stopped between steps (a Safe-stop or a pause raised mid-step)."""
    _invoke(ctx, None)


def waiting_on(ctx: RunContext) -> dict[str, Any] | None:
    """What the Run is waiting for (an answer or an approval), or None."""
    with open_graph(ctx) as graph:
        interrupts = graph.get_state(_config(ctx)).interrupts
    return dict(interrupts[0].value) if interrupts else None


def resume(ctx: RunContext, value: dict[str, Any]) -> None:
    _invoke(ctx, Command(resume=value))
