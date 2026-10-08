"""Requirements Stage and the spec Approval Checkpoint (#27), through the CLI seam."""

import json
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.agent import StepRequest, StepResult
from orchestrator.context import marker

SPEC_PATH = "docs/specs/0003-expiring-links.md"


def spec_text(roadmap: str = "R18", omit: str | None = None) -> str:
    sections = {
        "## Problem Statement": "Links live forever.",
        "## Solution": "Links can expire.",
        "## User Stories": "1. As a creator, I want a Link to expire, so that it stops working.",
        "## Implementation Decisions": "- Expiry is a timestamp.",
        "## Testing Decisions": "- Through the HTTP seam.",
        "## Out of Scope": "- Click-count expiry.",
        "## Further Notes": "None.",
    }
    body = "\n\n".join(f"{h}\n\n{text}" for h, text in sections.items() if h != omit)
    return f"---\nstatus: draft\nroadmap: {roadmap}\n---\n\n# Spec 0003: Expiring links\n\n{body}\n"


def question(text: str, recommendation: str = "Option a") -> StepResult:
    return StepResult(output={"question": text, "recommendation": recommendation}, cost_usd=0.1)


def writes_spec(text: str, path: str = SPEC_PATH) -> Callable[[StepRequest], StepResult]:
    def write(request: StepRequest) -> StepResult:
        target = request.workspace / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        return StepResult(output={"spec": path}, files_changed=(path,), cost_usd=0.2)

    return write


def events(repo: Path, run: str = "R-0001") -> list[dict[str, Any]]:
    path = repo / ".orchestrator" / "runs" / run / "events.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()]


def types(repo: Path) -> list[str]:
    return [e["type"] for e in events(repo)]


def remote_file(repo: Path, branch: str, path: str) -> str:
    remote = repo.parent / "remote.git"
    return subprocess.run(
        ["git", "--git-dir", str(remote), "show", f"{branch}:{path}"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def stage_state(out: str, stage: str) -> str:
    return next(line.split()[1] for line in out.splitlines() if line.startswith(f"  {stage} "))


@pytest.fixture
def issue(github: InMemoryGitHub) -> int:
    github.add_issue(42, "Expiring links", "Links should be able to expire. Roadmap R18.")
    return 42


def submit_spec(orchestrate: Orchestrate, agent: ScriptedAgent, text: str | None = None) -> None:
    agent.script["requirements"] = [writes_spec(text or spec_text())]
    code, out = orchestrate("start", "42")
    assert code == 0, out


# Clarifying questions


def test_the_first_question_is_posted_on_the_issue_and_the_run_waits(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, issue: int
) -> None:
    agent.script["requirements"] = [question("Expire by date or by clicks?", "By date")]

    orchestrate("start", "42")

    [comment] = [c for c in github.comments(42) if "Q1" in c.body]
    assert "Expire by date or by clicks?" in comment.body
    assert "Recommended" in comment.body and "By date" in comment.body
    _, out = orchestrate("status", "R-0001")
    assert stage_state(out, "requirements") == "running"
    assert "Waiting for: an answer to Q1 on Issue #42" in out


def test_resume_without_a_reply_keeps_waiting_without_asking_again(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, issue: int
) -> None:
    agent.script["requirements"] = [question("Expire by date or by clicks?")]
    orchestrate("start", "42")
    comments_before = len(github.comments(42))

    code, out = orchestrate("resume", "R-0001")

    assert code == 0
    assert "Waiting for: an answer to Q1" in out
    assert len(agent.requests) == 1
    assert len(github.comments(42)) == comments_before


def test_a_reply_is_recorded_and_the_next_question_follows_one_at_a_time(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, issue: int
) -> None:
    agent.script["requirements"] = [
        question("Expire by date or by clicks?", "By date"),
        question("What does a visitor see after expiry?", "410 Gone"),
    ]
    orchestrate("start", "42")
    github.reply(42, "By date, please.", author="maintainer")

    orchestrate("resume", "R-0001")

    second = agent.requests[1].context
    assert second["answers"] == [
        {
            "question": "Expire by date or by clicks?",
            "recommendation": "By date",
            "answer": "By date, please.",
            "by": "maintainer",
        }
    ]
    assert any("Q2" in c.body and "410 Gone" in c.body for c in github.comments(42))
    answered = [e for e in events(repo) if e["type"] == "answer_received"]
    assert [(e["actor"], e["data"]["by"]) for e in answered] == [("engineer", "maintainer")]


def test_orchestrator_comments_are_never_mistaken_for_answers(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, issue: int
) -> None:
    agent.script["requirements"] = [question("Expire by date or by clicks?")]
    orchestrate("start", "42")
    github.add_comment(42, f"{marker('R-0001')}\nA later progress note from the orchestrator.")

    _, out = orchestrate("resume", "R-0001")

    assert len(agent.requests) == 1
    assert "Waiting for: an answer to Q1" in out


# The spec and its Exit Gate


def test_a_spec_that_passes_its_gate_is_committed_pushed_and_submitted_for_approval(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, issue: int
) -> None:
    submit_spec(orchestrate, agent)

    assert remote_file(repo, "docs/run-R-0001", SPEC_PATH) == spec_text()
    [request] = [c for c in github.comments(42) if "Approval needed" in c.body]
    assert f"https://github.test/blob/docs/run-R-0001/{SPEC_PATH}" in request.body
    assert "orchestrate approve R-0001 spec" in request.body and "approved:spec" in request.body
    _, out = orchestrate("status", "R-0001")
    assert "Approvals waiting: spec" in out
    assert types(repo)[-2:] == ["gate_result", "approval_requested"]


@pytest.mark.parametrize(
    "missing",
    ["## Problem Statement", "## User Stories", "## Testing Decisions", "## Out of Scope"],
)
def test_the_gate_names_a_missing_section_and_the_agent_revises(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, issue: int, missing: str
) -> None:
    agent.script["requirements"] = [writes_spec(spec_text(omit=missing)), writes_spec(spec_text())]

    orchestrate("start", "42")

    assert missing in agent.requests[1].context["feedback"]
    assert any("gate failed" in c.body.lower() and missing in c.body for c in github.comments(42))
    assert any("Approval needed" in c.body for c in github.comments(42))


def test_the_gate_requires_user_stories(
    orchestrate: Orchestrate, agent: ScriptedAgent, issue: int
) -> None:
    no_stories = spec_text().replace("1. As a creator,", "A creator")
    agent.script["requirements"] = [writes_spec(no_stories), writes_spec(spec_text())]

    orchestrate("start", "42")

    assert "user stor" in agent.requests[1].context["feedback"].lower()


def test_the_gate_requires_the_runs_roadmap_item(
    orchestrate: Orchestrate, agent: ScriptedAgent, issue: int
) -> None:
    agent.script["requirements"] = [writes_spec(spec_text(roadmap="R10")), writes_spec(spec_text())]

    orchestrate("start", "42")

    assert "R18" in agent.requests[1].context["feedback"]


def test_the_gate_requires_a_new_numbered_spec_file(
    orchestrate: Orchestrate, agent: ScriptedAgent, issue: int
) -> None:
    agent.script["requirements"] = [
        writes_spec(spec_text(), path="docs/expiring-links.md"),
        writes_spec(spec_text()),
    ]

    orchestrate("start", "42")

    assert "docs/specs/NNNN-" in agent.requests[1].context["feedback"]


def test_a_gate_that_keeps_failing_fails_the_stage_and_pauses_the_run(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, issue: int
) -> None:
    broken = spec_text(omit="## Solution")
    agent.script["requirements"] = [writes_spec(broken) for _ in range(3)]

    orchestrate("start", "42")

    assert len(agent.requests) == 3  # the first attempt plus max_retries (2)
    assert types(repo)[-2:] == ["stage_failed", "paused"]
    _, out = orchestrate("status", "R-0001")
    assert stage_state(out, "requirements") == "failed"
    assert "State: paused" in out
    assert any("paused" in c.body.lower() for c in github.comments(42))


# The spec Approval Checkpoint


def test_approving_from_the_cli_passes_the_stage_and_binds_the_approval_to_the_spec(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, issue: int
) -> None:
    submit_spec(orchestrate, agent)
    submitted = next(e for e in events(repo) if e["type"] == "approval_requested")

    code, out = orchestrate("approve", "R-0001", "spec")

    assert code == 0
    approved = next(e for e in events(repo) if e["type"] == "approved")
    assert approved["actor"] == "engineer"
    assert approved["data"] == {
        "checkpoint": "spec",
        "by": "maintainer",
        "channel": "cli",
        "hash": submitted["data"]["hash"],
    }
    passed = events(repo)[-1]
    assert (passed["stage"], passed["type"]) == ("requirements", "stage_passed")
    assert passed["data"]["artifacts"]["spec"] == {
        "path": SPEC_PATH,
        "hash": submitted["data"]["hash"],
    }
    assert any("approved" in c.body.lower() and "maintainer" in c.body for c in github.comments(42))
    code, out = orchestrate("status", "R-0001")
    assert stage_state(out, "requirements") == "passed"
    assert "Approvals waiting: none" in out


def test_a_maintainers_label_approves_on_resume(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, issue: int
) -> None:
    submit_spec(orchestrate, agent)
    github.add_label(42, "approved:spec", by="maintainer")

    orchestrate("resume", "R-0001")

    approved = next(e for e in events(repo) if e["type"] == "approved")
    assert (approved["data"]["by"], approved["data"]["channel"]) == ("maintainer", "label")


def test_a_label_from_someone_who_is_not_a_maintainer_is_ignored(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, issue: int
) -> None:
    submit_spec(orchestrate, agent)
    github.add_label(42, "approved:spec", by="drive-by")

    _, out = orchestrate("resume", "R-0001")

    assert "approved" not in types(repo)
    assert "Approvals waiting: spec" in out


def test_rejecting_sends_the_reason_back_and_the_revised_spec_needs_a_fresh_approval(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, issue: int
) -> None:
    revised = spec_text().replace("Links can expire.", "Links can expire at a chosen date.")
    agent.script["requirements"] = [writes_spec(spec_text()), writes_spec(revised)]
    orchestrate("start", "42")
    github.add_label(42, "approved:spec", by="maintainer")  # applied, then the spec is rejected

    code, _ = orchestrate("reject", "R-0001", "spec", "--reason", "Say how expiry is chosen.")

    assert code == 0
    assert agent.requests[-1].context["feedback"] == "Say how expiry is chosen."
    assert remote_file(repo, "docs/run-R-0001", SPEC_PATH) == revised
    assert types(repo).count("approval_requested") == 2
    rejected = next(e for e in events(repo) if e["type"] == "rejected")
    assert rejected["data"]["reason"] == "Say how expiry is chosen."
    orchestrate("resume", "R-0001")
    assert "approved" not in types(repo)  # the earlier label doesn't approve the revision


def test_a_spec_changed_after_submission_cannot_be_approved(
    orchestrate: Orchestrate, agent: ScriptedAgent, repo: Path, issue: int
) -> None:
    submit_spec(orchestrate, agent)
    (repo / ".orchestrator" / "worktrees" / "R-0001" / SPEC_PATH).write_text(spec_text() + "\nx\n")

    code, out = orchestrate("approve", "R-0001", "spec")

    assert code != 0
    assert "changed" in out
    assert "approved" not in types(repo)


def test_approve_is_refused_when_nothing_is_waiting(
    orchestrate: Orchestrate, agent: ScriptedAgent, issue: int
) -> None:
    agent.script["requirements"] = [question("Expire by date or by clicks?")]
    orchestrate("start", "42")

    code, out = orchestrate("approve", "R-0001", "spec")

    assert code != 0
    assert "not waiting" in out


def test_progress_is_mirrored_on_the_issue(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, issue: int
) -> None:
    submit_spec(orchestrate, agent)

    bodies = [c.body for c in github.comments(42)]

    assert any("R-0001" in b and "started" in b.lower() for b in bodies)
    assert any("requirements" in b.lower() and "started" in b.lower() for b in bodies)
    assert all("<!-- orchestrator:R-0001 -->" in b for b in bodies)
