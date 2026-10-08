"""Policy guardrails inside a Run (#32): blocked before the action, verified after the step."""

import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from test_lane_end_to_end import git, lane_script, merge, on_remote, through_docs_pr, writes
from test_policy_check import TOKEN
from test_requirements_stage import spec_text, writes_spec

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.agent import StepRequest, StepResult
from orchestrator.policies import Decision, ToolCall


def events(repo: Path) -> list[dict[str, Any]]:
    path = repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()]


def of_type(repo: Path, type: str) -> list[dict[str, Any]]:
    return [e for e in events(repo) if e["type"] == type]


def tries(
    *calls: ToolCall, then: Callable[[StepRequest], StepResult], seen: list[Decision]
) -> Callable[[StepRequest], StepResult]:
    """An agent step that attempts actions through the guard, as the real hook does, then works."""

    def step(request: StepRequest) -> StepResult:
        seen.extend(request.guard(call) for call in calls)
        return then(request)

    return step


PUSH_MAIN = ToolCall("Bash", {"command": "git push origin main"})
MERGE = ToolCall("Bash", {"command": "gh pr merge 7"})
SUDO = ToolCall("Bash", {"command": "sudo true"})


# Before the action


def test_a_blocked_action_is_refused_reported_to_the_agent_and_recorded(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    seen: list[Decision] = []
    lane_script(agent, tries(PUSH_MAIN, then=writes({"feature.txt": "x"}), seen=seen))

    through_docs_pr(orchestrate, github, repo, published)

    assert [d.allowed for d in seen] == [False]
    assert "pushing to main is not allowed" in seen[0].reason
    [blocked] = of_type(repo, "policy_blocked")
    assert blocked["stage"] == "implement"
    assert blocked["data"] == {"tool": "Bash", "rule": "git", "reason": seen[0].reason}
    assert github.pr_for_head("feat/100-store-expiry-on-a-link")  # the Run carried on


def test_allowed_actions_pass_the_guard(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    seen: list[Decision] = []
    ok = ToolCall("Bash", {"command": "./mvnw -B verify"})
    lane_script(agent, tries(ok, then=writes({"feature.txt": "x"}), seen=seen))

    through_docs_pr(orchestrate, github, repo, published)

    assert seen == [Decision(True)]
    assert of_type(repo, "policy_blocked") == []


def test_repeated_blocks_in_one_step_pause_the_run_and_resume_retries_the_step(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    seen: list[Decision] = []
    lane_script(
        agent,
        tries(PUSH_MAIN, MERGE, SUDO, then=writes({"feature.txt": "x"}), seen=seen),
        writes({"feature.txt": "x"}),
    )
    docs_pr = published()
    merge(repo, github, docs_pr)

    _, out = orchestrate("resume", "R-0001")

    assert "State: paused" in out
    assert (
        of_type(repo, "paused")[-1]["data"]["reason"]
        == "3 actions were blocked by policy in one step"
    )
    assert any("Paused in **implement**" in c.body for c in github.comments(42))

    _, out = orchestrate("resume", "R-0001")

    assert len([r for r in agent.requests if r.stage == "implement"]) == 2
    assert "State: active" in out
    assert of_type(repo, "resumed")
    assert github.pr_for_head("feat/100-store-expiry-on-a-link")


# After the step


def test_a_protected_file_changed_without_asking_fails_the_gate_and_is_never_committed(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    def write_then_revert(request: StepRequest) -> StepResult:
        (request.workspace / "CLAUDE.md").unlink(missing_ok=True)
        (request.workspace / "feature.txt").write_text("x")
        return StepResult()

    lane_script(
        agent, writes({"CLAUDE.md": "relaxed rules\n", "feature.txt": "x"}), write_then_revert
    )

    pr = through_docs_pr(orchestrate, github, repo, published)

    implement = [r for r in agent.requests if r.stage == "implement"]
    assert "CLAUDE.md is protected" in implement[1].context["feedback"]
    head = github.pr(pr).head
    files = git(
        repo, "--git-dir", str(repo.parent / "remote.git"), "ls-tree", "-r", "--name-only", head
    )
    assert "CLAUDE.md" not in files.split()


def test_a_credential_in_the_diff_fails_the_gate_and_never_reaches_the_record(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(
        agent,
        writes({"feature.txt": "x", "src/Config.java": f'String t = "{TOKEN}";\n'}),
        writes({"feature.txt": "x", "src/Config.java": 'String t = System.getenv("T");\n'}),
    )

    through_docs_pr(orchestrate, github, repo, published)

    feedback = [r for r in agent.requests if r.stage == "implement"][1].context["feedback"]
    assert "src/Config.java: added line 1 looks like a credential (github_token)" in feedback
    assert TOKEN not in feedback
    log = (repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl").read_text()
    assert TOKEN not in log
    assert all(TOKEN not in c.body for c in github.comments(42))


def test_an_adr_written_while_implementing_fails_the_gate(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    def remove_adr(request: StepRequest) -> StepResult:
        (request.workspace / "docs" / "adr" / "0099-sneaky.md").unlink()
        (request.workspace / "feature.txt").write_text("x")
        return StepResult()

    lane_script(agent, writes({"docs/adr/0099-sneaky.md": "# x", "feature.txt": "x"}), remove_adr)

    through_docs_pr(orchestrate, github, repo, published)

    feedback = [r for r in agent.requests if r.stage == "implement"][1].context["feedback"]
    assert "docs/adr/0099-sneaky.md may only be written in the design Stage" in feedback


def test_the_requirements_gate_also_reviews_what_the_agent_changed(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")

    def spec_and_workflow(request: StepRequest) -> StepResult:
        (request.workspace / ".github" / "workflows").mkdir(parents=True)
        (request.workspace / ".github" / "workflows" / "x.yml").write_text("on: push\n")
        return writes_spec(spec_text())(request)

    agent.script["requirements"] = [spec_and_workflow, writes_spec(spec_text())]

    orchestrate("start", "42")

    assert ".github/workflows/x.yml is protected" in agent.requests[1].context["feedback"]


# High-impact changes: dependencies


def test_a_dependency_change_waits_for_its_own_approval(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(
        agent, writes({"feature.txt": "x", "pom.xml": "<project><dependency/></project>\n"})
    )
    docs_pr = published()
    merge(repo, github, docs_pr)

    _, out = orchestrate("resume", "R-0001")

    assert "Approvals waiting: dependency:T1" in out
    request = next(
        c for c in github.comments(42) if "dependency:T1" in c.body and "Approval needed" in c.body
    )
    assert "pom.xml" in request.body and "feat/100-store-expiry-on-a-link" in request.body
    assert on_remote(repo, "feat/100-store-expiry-on-a-link", "pom.xml").startswith("<project>")
    assert not [e for e in of_type(repo, "gate_result") if e["data"]["gate"] == "verify passes"]

    code, out = orchestrate("approve", "R-0001", "dependency:T1")

    assert code == 0, out
    approved = of_type(repo, "approved")[-1]["data"]
    assert (approved["checkpoint"], approved["by"]) == ("dependency:T1", "maintainer")
    assert github.pr_for_head("feat/100-store-expiry-on-a-link")


def test_a_rejected_dependency_change_goes_back_to_implement(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    def revert_pom(request: StepRequest) -> StepResult:
        git(request.workspace, "checkout", "origin/main", "--", ".")
        (request.workspace / "pom.xml").unlink(missing_ok=True)
        (request.workspace / "feature.txt").write_text("x")
        return StepResult()

    lane_script(agent, writes({"feature.txt": "x", "pom.xml": "<project/>\n"}), revert_pom)
    docs_pr = published()
    merge(repo, github, docs_pr)
    orchestrate("resume", "R-0001")

    orchestrate("reject", "R-0001", "dependency:T1", "--reason", "No new libraries for this.")

    feedback = [r for r in agent.requests if r.stage == "implement"][1].context["feedback"]
    assert feedback == "dependency:T1 rejected: No new libraries for this."


def test_a_dependency_file_changed_after_submission_cannot_be_approved(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent, writes({"feature.txt": "x", "pom.xml": "<project/>\n"}))
    docs_pr = published()
    merge(repo, github, docs_pr)
    orchestrate("resume", "R-0001")
    (repo / ".orchestrator" / "worktrees" / "R-0001-100" / "pom.xml").write_text(
        "<project>changed</project>\n"
    )

    code, out = orchestrate("approve", "R-0001", "dependency:T1")

    assert code != 0 and "changed" in out


# The policy file


def test_the_run_records_the_policies_it_runs_under(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(42, "Expiring links")

    orchestrate("start", "42")

    from orchestrator.policies import load_policies

    assert events(repo)[0]["data"]["policies"] == load_policies(repo).digest


@pytest.mark.parametrize("damage", ["missing", "invalid"])
def test_a_run_will_not_start_without_valid_policies(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path, damage: str
) -> None:
    github.add_issue(42, "Expiring links")
    policy = repo / "orchestrator" / "policies.yaml"
    if damage == "missing":
        policy.unlink()
    else:
        policy.write_text("version: 1\n")

    code, out = orchestrate("start", "42")

    assert code != 0 and "policies.yaml" in out
    assert not (repo / ".orchestrator" / "runs").exists()
