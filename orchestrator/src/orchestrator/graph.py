"""Builds the Stage graph and runs it: until it finishes, waits on a human, or pauses (ADR 0008)."""

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any, Literal

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command

from orchestrator import decompose, design, lanes, requirements
from orchestrator.approvals import AwaitApproval, RequestApproval
from orchestrator.closeout import CloseOut, ReleaseReadiness
from orchestrator.context import RunContext, RunPaused
from orchestrator.stages import Intake
from orchestrator.state import RunState

Graph = CompiledStateGraph[RunState, None, RunState, RunState]


def _after_intake(state: RunState) -> Literal["next", "stop"]:
    return "next" if state.get("intake_passed") else "stop"


def build(ctx: RunContext, checkpointer: SqliteSaver) -> Graph:
    g = StateGraph(RunState)
    g.add_node("intake", Intake(ctx))
    g.add_edge(START, "intake")
    g.add_conditional_edges("intake", _after_intake, {"next": "requirements", "stop": END})

    # requirements: questions, spec, spec gate, spec approval
    g.add_node("requirements", requirements.Begin(ctx))
    g.add_node("requirements_draft", requirements.Draft(ctx))
    g.add_node("requirements_await_answer", requirements.AwaitAnswer(ctx))
    g.add_node("requirements_gate", requirements.Gate(ctx))
    g.add_node(
        "requirements_request_approval",
        RequestApproval(ctx, requirements.STAGE, requirements.describe_spec),
    )
    g.add_node("requirements_await_approval", AwaitApproval(ctx, requirements.STAGE))
    g.add_node("requirements_decided", requirements.Decided(ctx))
    g.add_edge("requirements", "requirements_draft")
    g.add_conditional_edges(
        "requirements_draft",
        requirements.after_draft,
        {"await_answer": "requirements_await_answer", "gate": "requirements_gate"},
    )
    g.add_edge("requirements_await_answer", "requirements_draft")
    g.add_conditional_edges(
        "requirements_gate",
        requirements.after_gate,
        {
            "request_approval": "requirements_request_approval",
            "draft": "requirements_draft",
            "stop": END,
        },
    )
    g.add_edge("requirements_request_approval", "requirements_await_approval")
    g.add_edge("requirements_await_approval", "requirements_decided")
    g.add_conditional_edges(
        "requirements_decided",
        requirements.after_decided,
        {"draft": "requirements_draft", "next": "design"},
    )

    # design: ADRs (or none), each approved separately
    g.add_node("design", design.Begin(ctx))
    g.add_node("design_draft", design.Draft(ctx))
    g.add_node("design_gate", design.Gate(ctx))
    g.add_node(
        "design_request_approval", RequestApproval(ctx, design.STAGE, design.DescribeAdr(ctx))
    )
    g.add_node("design_await_approval", AwaitApproval(ctx, design.STAGE))
    g.add_node("design_decided", design.Decided(ctx))
    g.add_node("design_done", design.Done(ctx))
    g.add_edge("design", "design_draft")
    g.add_edge("design_draft", "design_gate")
    g.add_conditional_edges(
        "design_gate",
        design.after_gate,
        {
            "request_approval": "design_request_approval",
            "draft": "design_draft",
            "done": "design_done",
            "stop": END,
        },
    )
    g.add_edge("design_request_approval", "design_await_approval")
    g.add_edge("design_await_approval", "design_decided")
    g.add_conditional_edges(
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
    g.add_node("decompose", decompose.Begin(ctx))
    g.add_node("decompose_draft", decompose.Draft(ctx))
    g.add_node("decompose_gate", decompose.Gate(ctx))
    g.add_node(
        "decompose_request_approval",
        RequestApproval(ctx, decompose.STAGE, decompose.describe_tickets),
    )
    g.add_node("decompose_await_approval", AwaitApproval(ctx, decompose.STAGE))
    g.add_node("decompose_decided", decompose.Decided(ctx))
    g.add_node("decompose_publish", decompose.Publish(ctx))
    g.add_edge("decompose", "decompose_draft")
    g.add_edge("decompose_draft", "decompose_gate")
    g.add_conditional_edges(
        "decompose_gate",
        decompose.after_gate,
        {"request_approval": "decompose_request_approval", "draft": "decompose_draft", "stop": END},
    )
    g.add_edge("decompose_request_approval", "decompose_await_approval")
    g.add_edge("decompose_await_approval", "decompose_decided")
    g.add_conditional_edges(
        "decompose_decided",
        decompose.after_decided,
        {"publish": "decompose_publish", "draft": "decompose_draft"},
    )
    g.add_edge("decompose_publish", "lanes")

    # lanes: the Run's documents reach main first, then each ticket in dependency order
    g.add_node("lanes", lanes.Begin(ctx))
    g.add_node("docs_checks", lanes.AwaitChecks(ctx, lanes.docs_pr, lanes.docs_sha))
    g.add_node("docs_checks_failed", lanes.DocsChecksFailed(ctx))
    g.add_node("docs_request_merge", lanes.RequestMerge(ctx, lanes.docs_pr))
    g.add_node("docs_await_merge", lanes.AwaitMerge(ctx, lanes.docs_pr))
    g.add_node("lane_begin", lanes.LaneBegin(ctx))
    g.add_node("implement", lanes.Implement(ctx))
    g.add_node(
        "dependency_request_approval",
        RequestApproval(ctx, "implement", lanes.describe_dependency, lanes.dependency_location),
    )
    g.add_node("dependency_await_approval", AwaitApproval(ctx, "implement"))
    g.add_node("dependency_decided", lanes.DependencyDecided(ctx))
    g.add_node("verify", lanes.Verify(ctx))
    g.add_node("document", lanes.Document(ctx))
    g.add_node("open_pr", lanes.OpenPr(ctx))
    g.add_node("lane_checks", lanes.AwaitChecks(ctx, lanes.lane_pr, lanes.lane_sha))
    g.add_node("ci_failed", lanes.CiFailed(ctx))
    g.add_node("lane_request_merge", lanes.RequestMerge(ctx, lanes.lane_pr))
    g.add_node("lane_await_merge", lanes.AwaitMerge(ctx, lanes.lane_pr))
    g.add_node("lane_merged", lanes.LaneMerged(ctx))
    g.add_edge("lanes", "docs_checks")
    g.add_conditional_edges(
        "docs_checks",
        lanes.after_docs_checks,
        {
            "recheck": "docs_checks",
            "request_merge": "docs_request_merge",
            "stop": "docs_checks_failed",
        },
    )
    g.add_edge("docs_checks_failed", END)
    g.add_edge("docs_request_merge", "docs_await_merge")
    g.add_conditional_edges(
        "docs_await_merge",
        lanes.after_merge,
        {"recheck": "docs_await_merge", "next": "lane_begin", "stop": END},
    )
    g.add_edge("lane_begin", "implement")
    g.add_conditional_edges(
        "implement",
        lanes.after_implement,
        {
            "verify": "verify",
            "dependency": "dependency_request_approval",
            "implement": "implement",
            "stop": END,
        },
    )
    g.add_edge("dependency_request_approval", "dependency_await_approval")
    g.add_edge("dependency_await_approval", "dependency_decided")
    g.add_conditional_edges(
        "dependency_decided",
        lanes.after_dependency,
        {"verify": "verify", "implement": "implement"},
    )
    g.add_conditional_edges(
        "verify",
        lanes.after_verify,
        {"document": "document", "implement": "implement", "stop": END},
    )
    g.add_conditional_edges(
        "document",
        lanes.after_document,
        {"open_pr": "open_pr", "document": "document", "stop": END},
    )
    g.add_edge("open_pr", "lane_checks")
    g.add_conditional_edges(
        "lane_checks",
        lanes.after_lane_checks,
        {"recheck": "lane_checks", "request_merge": "lane_request_merge", "failed": "ci_failed"},
    )
    g.add_conditional_edges(
        "ci_failed", lanes.after_ci_failed, {"implement": "implement", "stop": END}
    )
    g.add_edge("lane_request_merge", "lane_await_merge")
    g.add_conditional_edges(
        "lane_await_merge",
        lanes.after_merge,
        {"recheck": "lane_await_merge", "next": "lane_merged", "stop": END},
    )
    g.add_conditional_edges(
        "lane_merged",
        lanes.after_lane,
        {"next_lane": "lane_begin", "readiness": "release_readiness"},
    )

    # release readiness and close-out
    g.add_node("release_readiness", ReleaseReadiness(ctx))
    g.add_node("close_out", CloseOut(ctx))
    g.add_conditional_edges(
        "release_readiness",
        lambda state: "stop" if state.get("paused") else "close_out",
        {"stop": END, "close_out": "close_out"},
    )
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
    try:
        with open_graph(ctx) as graph:
            graph.invoke(value, _config(ctx))
    except RunPaused as paused:
        ctx.event("paused", paused.stage, {"reason": paused.reason})
        ctx.mirror(
            f"⏸️ Paused in **{paused.stage}**: {paused.reason}. "
            f"Resume with `orchestrate resume {ctx.run}`."
        )


def start(ctx: RunContext) -> None:
    _invoke(ctx, {"run": ctx.run, "issue": ctx.issue})


def pending_step(ctx: RunContext) -> bool:
    """True when the Run stopped mid-step (a Safe-stop) and can continue from there."""
    with open_graph(ctx) as graph:
        snapshot = graph.get_state(_config(ctx))
    return bool(snapshot.next) and not snapshot.interrupts


def continue_paused(ctx: RunContext) -> None:
    ctx.event("resumed", None, actor="engineer")
    _invoke(ctx, None)


def waiting_on(ctx: RunContext) -> dict[str, Any] | None:
    """What the Run is waiting for (an answer or an approval), or None."""
    with open_graph(ctx) as graph:
        interrupts = graph.get_state(_config(ctx)).interrupts
    return dict(interrupts[0].value) if interrupts else None


def resume(ctx: RunContext, value: dict[str, Any]) -> None:
    _invoke(ctx, Command(resume=value))
