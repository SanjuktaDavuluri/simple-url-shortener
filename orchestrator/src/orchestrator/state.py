"""The Run's graph state, saved in LangGraph checkpoints (`.orchestrator/state.db`)."""

from typing import Any, TypedDict


class RunState(TypedDict, total=False):
    run: str
    issue: int
    intake_passed: bool
    roadmap_item: str | None
    answers: list[dict[str, Any]]
    pending_question: dict[str, Any] | None
    feedback: str | None
    attempts: int
    paused: bool
    spec_path: str
    spec_hash: str
    approval_requested_at: str
    spec_approved: bool
