"""The Stage graph (ADR 0008). Stages after intake arrive with tickets #27 to #29."""

import re
import sqlite3
from typing import Any, TypedDict, cast

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph

from orchestrator.github import Issue
from orchestrator.hashing import content_hash
from orchestrator.workspace import Workspace

STAGES = (
    "intake",
    "requirements",
    "design",
    "decompose",
    "lanes",
    "release_readiness",
    "close_out",
)

ROADMAP_ROW = re.compile(r"^\|\s*(R\d+)\s*\|", re.MULTILINE)
ROADMAP_REF = re.compile(r"\bR\d+\b")


class RunState(TypedDict, total=False):
    run: str
    issue: int
    roadmap_item: str | None


def roadmap_items(workspace: Workspace) -> set[str]:
    path = workspace.repo_root / "docs" / "roadmap.md"
    return set(ROADMAP_ROW.findall(path.read_text())) if path.exists() else set()


def issue_artifact(issue: Issue) -> dict[str, Any]:
    labels = sorted(issue.labels)
    return {
        "number": issue.number,
        "title": issue.title.strip(),
        "labels": labels,
        "hashes": {
            "title": content_hash(issue.title),
            "body": content_hash(issue.body),
            "labels": content_hash("\n".join(labels)),
        },
    }


class Intake:
    """Fix the Run's starting point: the Issue as it is now, and the roadmap item it names."""

    def __init__(self, workspace: Workspace, issue: Issue) -> None:
        self.workspace = workspace
        self.issue = issue

    def __call__(self, state: RunState) -> RunState:
        run, issue = state["run"], self.issue
        log = self.workspace.log(run)
        log.append(run=run, actor="orchestrator", stage="intake", type="stage_started")
        has_request = bool(issue.title.strip())
        log.append(
            run=run,
            actor="orchestrator",
            stage="intake",
            type="gate_result",
            data={"gate": "issue has a request", "passed": has_request},
        )
        if not has_request:
            log.append(run=run, actor="orchestrator", stage="intake", type="stage_failed")
            return {}
        known = roadmap_items(self.workspace)
        named = [ref for ref in ROADMAP_REF.findall(f"{issue.title}\n{issue.body}") if ref in known]
        roadmap_item = named[0] if named else None
        log.append(
            run=run,
            actor="orchestrator",
            stage="intake",
            type="stage_passed",
            data={"artifacts": {"issue": issue_artifact(issue)}, "roadmap_item": roadmap_item},
        )
        return {"roadmap_item": roadmap_item}


def run_graph(workspace: Workspace, issue: Issue, initial: RunState) -> RunState:
    graph = StateGraph(RunState)
    graph.add_node("intake", Intake(workspace, issue))
    graph.add_edge(START, "intake")
    graph.add_edge("intake", END)
    workspace.state_dir.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(workspace.checkpoint_db, check_same_thread=False) as conn:
        compiled = graph.compile(checkpointer=SqliteSaver(conn))
        result = compiled.invoke(initial, {"configurable": {"thread_id": initial["run"]}})
    return cast(RunState, result)
