"""Builds the Stage graph and runs it: until it finishes, waits on a human, or pauses (ADR 0008)."""

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any, Literal

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command

from orchestrator import requirements
from orchestrator.context import RunContext
from orchestrator.stages import Intake
from orchestrator.state import RunState

Graph = CompiledStateGraph[RunState, None, RunState, RunState]


def _after_intake(state: RunState) -> Literal["requirements_begin", "stop"]:
    return "requirements_begin" if state.get("intake_passed") else "stop"


def build(ctx: RunContext, checkpointer: SqliteSaver) -> Graph:
    g = StateGraph(RunState)
    g.add_node("intake", Intake(ctx))
    g.add_node("requirements_begin", requirements.Begin(ctx))
    g.add_node("requirements_draft", requirements.Draft(ctx))
    g.add_node("requirements_await_answer", requirements.AwaitAnswer(ctx))
    g.add_node("requirements_gate", requirements.Gate(ctx))
    g.add_node("requirements_request_approval", requirements.RequestApproval(ctx))
    g.add_node("requirements_await_approval", requirements.AwaitApproval(ctx))
    g.add_edge(START, "intake")
    g.add_conditional_edges(
        "intake", _after_intake, {"requirements_begin": "requirements_begin", "stop": END}
    )
    g.add_edge("requirements_begin", "requirements_draft")
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
    g.add_conditional_edges(
        "requirements_await_approval",
        requirements.after_approval,
        {"draft": "requirements_draft", "done": END},
    )
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
