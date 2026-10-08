"""The Stage list (ADR 0008) and the intake Stage."""

import re
from typing import Any

from orchestrator.context import RunContext
from orchestrator.github import Issue
from orchestrator.hashing import content_hash
from orchestrator.state import RunState
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


def roadmap_items(workspace: Workspace) -> set[str]:
    path = workspace.repo_root / "docs" / "roadmap.md"
    return set(ROADMAP_ROW.findall(path.read_text())) if path.exists() else set()


def roadmap_release(workspace: Workspace, item: str | None) -> str | None:
    """The Release a roadmap item is planned in (the table's third column), if it is a number."""
    path = workspace.repo_root / "docs" / "roadmap.md"
    if not item or not path.exists():
        return None
    for line in path.read_text().splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) > 2 and cells[0] == item and cells[2].isdigit():
            return cells[2]
    return None


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

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        issue = ctx.github.get_issue(ctx.issue)
        ctx.mirror(
            f"Run **{ctx.run}** started for this Issue. "
            f"Follow it with `orchestrate status {ctx.run}`."
        )
        ctx.event("stage_started", "intake")
        has_request = bool(issue.title.strip())
        ctx.event("gate_result", "intake", {"gate": "issue has a request", "passed": has_request})
        if not has_request:
            ctx.event("stage_failed", "intake")
            return {"intake_passed": False}
        known = roadmap_items(ctx.workspace)
        named = [ref for ref in ROADMAP_REF.findall(f"{issue.title}\n{issue.body}") if ref in known]
        roadmap_item = named[0] if named else None
        ctx.event(
            "stage_passed",
            "intake",
            {"artifacts": {"issue": issue_artifact(issue)}, "roadmap_item": roadmap_item},
        )
        return {"intake_passed": True, "roadmap_item": roadmap_item}
