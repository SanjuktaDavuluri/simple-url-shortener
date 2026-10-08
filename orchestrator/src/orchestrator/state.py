"""The Run's graph state, saved in LangGraph checkpoints (`.orchestrator/state.db`)."""

from typing import Any, TypedDict


class RunState(TypedDict, total=False):
    run: str
    issue: int
    intake_passed: bool
    roadmap_item: str | None
    feedback: str | None
    attempts: int
    paused: bool
    approval: dict[str, Any]
    decision: dict[str, Any]
    # requirements
    answers: list[dict[str, Any]]
    pending_question: dict[str, Any] | None
    spec_path: str
    spec_hash: str
    spec: dict[str, str]
    # design
    adrs: list[str]
    no_adr_reason: str | None
    accepted_adrs: dict[str, str]
    pending_adrs: list[str]
    adrs_accepted: list[dict[str, str]]
    # decompose
    tickets: list[dict[str, Any]]
    tickets_hash: str
    published: dict[str, int]
