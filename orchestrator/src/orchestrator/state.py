"""The Run's graph state, saved in LangGraph checkpoints (`.orchestrator/state.db`)."""

from typing import Annotated, Any, TypedDict


def merge_lanes(
    old: list[dict[str, Any]] | None, new: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Lanes update concurrently (ADR 0020): an update replaces only the Lanes it names, by key."""
    updates = {ln["key"]: ln for ln in new}
    merged = [updates.pop(ln["key"], ln) for ln in old or []]
    return merged + list(updates.values())


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
    # lanes
    docs_pr: int
    docs_sha: str
    lanes: Annotated[list[dict[str, Any]], merge_lanes]
    current: str  # the Lane a parallel branch works on (only in its Send payload)
    ci: str
    ci_output: str
    merge: str
    close_out_pr: int
