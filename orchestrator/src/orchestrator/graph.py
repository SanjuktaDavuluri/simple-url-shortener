"""Builds the Stage graph and runs it: until it finishes, waits on a human, or pauses (ADR 0008)."""

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any, Literal

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command

from orchestrator import decompose, design, requirements
from orchestrator.approvals import AwaitApproval, RequestApproval
from orchestrator.context import RunContext
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
    g.add_edge("decompose_publish", END)  # Lanes arrive with #29
    return g.compile(checkpointer=checkpointer)


@contextmanager
def open_graph(ctx: RunContext) -> Iterator[Graph]:
    ctx.workspace.state_dir.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(ctx.workspace.checkpoint_db, check_same_thread=False) as conn:
        yield build(ctx, SqliteSaver(conn))


def _config(ctx: RunContext) -> Any:
    return {"configurable": {"thread_id": ctx.run}}


def start(ctx: RunContext) -> None:
    with open_graph(ctx) as graph:
        graph.invoke({"run": ctx.run, "issue": ctx.issue}, _config(ctx))


def waiting_on(ctx: RunContext) -> dict[str, Any] | None:
    """What the Run is waiting for (an answer or an approval), or None."""
    with open_graph(ctx) as graph:
        interrupts = graph.get_state(_config(ctx)).interrupts
    return dict(interrupts[0].value) if interrupts else None


def resume(ctx: RunContext, value: dict[str, Any]) -> None:
    with open_graph(ctx) as graph:
        graph.invoke(Command(resume=value), _config(ctx))
