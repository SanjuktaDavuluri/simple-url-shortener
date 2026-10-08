"""Failure handling (#31): bounded retries, pause and resume, Rollback, Safe-stop and cost caps."""

import json
from pathlib import Path
from typing import Any

from test_design_decompose import breakdown, ticket, writes_adrs
from test_lane_end_to_end import git, merge, writes
from test_requirements_stage import spec_text, writes_spec

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.agent import StepRequest, StepResult
from orchestrator.cli import Deps, main


def events(repo: Path) -> list[dict[str, Any]]:
    path = repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()]


def of_type(repo: Path, type: str) -> list[dict[str, Any]]:
    return [e for e in events(repo) if e["type"] == type]


def calls(agent: ScriptedAgent, stage: str) -> list[StepRequest]:
    return [r for r in agent.requests if r.stage == stage]


def remote_branches(repo: Path) -> list[str]:
    out = git(
        repo, "--git-dir", str(repo.parent / "remote.git"), "branch", "--format=%(refname:short)"
    )
    return out.split()


BROKEN = spec_text(omit="## Solution")


# Bounded retries, then a pause the engineer can resume


def test_each_retry_is_an_event_and_exhausted_retries_pause_with_the_last_failure(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(BROKEN) for _ in range(3)]

    _, out = orchestrate("start", "42")

    assert [e["data"]["attempt"] for e in of_type(repo, "retry")] == [1, 2]
    assert "State: paused" in out
    pause = next(c for c in github.comments(42) if "Paused" in c.body)
    assert "## Solution" in pause.body and "orchestrate resume R-0001" in pause.body


def test_resuming_a_paused_stage_retries_it_with_fresh_attempts(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [
        *(writes_spec(BROKEN) for _ in range(3)),
        writes_spec(spec_text()),
    ]
    orchestrate("start", "42")

    _, out = orchestrate("resume", "R-0001")

    assert len(calls(agent, "requirements")) == 4
    assert "Missing section: ## Solution" in calls(agent, "requirements")[3].context["feedback"]
    assert "State: active" in out and "Approvals waiting: spec" in out
    assert of_type(repo, "resumed")


# Rollback of one Lane; Lanes that passed are kept


def two_tickets(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    """T1 and T2 are independent; T3 depends on T1. The docs PR is merged."""
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
    agent.script["decompose"] = [
        breakdown(
            [
                ticket("T1", "Store expiry"),
                ticket("T2", "Show expiry"),
                ticket("T3", "Expire", ("T1",)),
            ]
        )
    ]
    orchestrate("start", "42")
    orchestrate("approve", "R-0001", "spec")
    orchestrate("approve", "R-0001", "tickets")
    merge(repo, github, github.pr_for_head("docs/run-R-0001"))


def test_a_lane_that_keeps_failing_pauses_and_can_be_rolled_back_keeping_the_others(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    probe: Path,
) -> None:
    two_tickets(orchestrate, github, agent, repo)
    agent.script["implement"] = [
        *(writes({"nothing.txt": "x"}) for _ in range(3)),
        writes({"feature.txt": "t2"}),
    ]
    agent.script["document"] = [writes({}, docs_updated=[])]

    _, out = orchestrate("resume", "R-0001")
    assert "State: paused" in out
    assert any("orchestrate reject R-0001 lane:T1" in c.body for c in github.comments(42))

    code, out = orchestrate("reject", "R-0001", "lane:T1", "--reason", "Needs a design rethink.")

    assert code == 0, out
    [rolled] = of_type(repo, "lane_rolled_back")
    assert rolled["data"]["lane"] == "T1" and rolled["data"]["reason"] == "Needs a design rethink."
    assert not (repo / ".orchestrator" / "worktrees" / "R-0001-100").exists()
    assert "feat/100-store-expiry" not in remote_branches(repo)
    assert github.board[100]["Status"] == "Todo"
    assert any("rolled back" in c.body and "Ready" in c.body for c in github.comments(100))
    t2_pr = github.pr_for_head("feat/101-show-expiry")
    merge(repo, github, t2_pr)
    _, out = orchestrate("resume", "R-0001")
    [skipped] = of_type(repo, "lane_skipped")
    assert skipped["data"] == {"lane": "T3", "issue": 102, "blocked_by": ["T1"]}
    assert "State: finished" in out
    finished = of_type(repo, "run_finished")[0]["data"]
    assert finished == {"outcome": "partially delivered", "rolled_back": ["T1"], "skipped": ["T3"]}
    report = git(
        repo,
        "--git-dir",
        str(repo.parent / "remote.git"),
        "show",
        "docs/run-R-0001-close-out:delivery/runs/R-0001/report.md",
    )
    assert "Rolled back" in report and "T1" in report and "T3" in report


def test_a_lane_pr_closed_without_merging_is_rolled_back(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    probe: Path,
) -> None:
    two_tickets(orchestrate, github, agent, repo)
    agent.script["implement"] = [writes({"feature.txt": "t1"}), writes({"feature.txt": "t2"})]
    agent.script["document"] = [writes({}, docs_updated=[]), writes({}, docs_updated=[])]
    orchestrate("resume", "R-0001")
    t1_pr = github.pr_for_head("feat/100-store-expiry")
    github.close_pr(t1_pr)

    orchestrate("resume", "R-0001")

    [rolled] = of_type(repo, "lane_rolled_back")
    assert rolled["data"]["pr"] == t1_pr
    assert "closed without merging" in rolled["data"]["reason"]
    assert "feat/100-store-expiry" not in remote_branches(repo)
    assert github.pr_for_head("feat/101-show-expiry")


def test_rollback_is_only_offered_for_a_paused_lane(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    orchestrate("start", "42")

    code, out = orchestrate("reject", "R-0001", "lane:T1", "--reason", "x")

    assert code != 0 and "not paused in Lane T1" in out


# Safe-stop


def test_stop_while_waiting_marks_the_run_stopped_and_resume_continues(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
    orchestrate("start", "42")

    code, out = orchestrate("stop", "R-0001")

    assert code == 0 and "State: stopped" in out
    assert of_type(repo, "safe_stop")[0]["actor"] == "engineer"
    code, out = orchestrate("approve", "R-0001", "spec")
    assert code != 0 and "stopped" in out

    orchestrate("resume", "R-0001")
    code, out = orchestrate("approve", "R-0001", "spec")
    assert code == 0, out
    assert calls(agent, "design")


def test_a_stop_label_halts_the_run_after_the_current_step(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")

    def spec_then_label(request: StepRequest) -> StepResult:
        github.set_labels(42, "stop")
        return writes_spec(spec_text())(request)

    agent.script["requirements"] = [spec_then_label]

    _, out = orchestrate("start", "42")

    assert "State: stopped" in out
    assert of_type(repo, "safe_stop")[0]["data"]["reason"] == "the stop label is on Issue #42"
    assert not [e for e in of_type(repo, "gate_result") if e["stage"] == "requirements"]
    code, out = orchestrate("resume", "R-0001")
    assert code != 0 and "stop label" in out

    github.set_labels(42)
    _, out = orchestrate("resume", "R-0001")

    assert len(calls(agent, "requirements")) == 1  # the finished step is not repeated
    assert "Approvals waiting: spec" in out


def test_stop_from_another_terminal_takes_effect_after_the_current_step(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")

    def spec_while_stop_is_requested(request: StepRequest) -> StepResult:
        main(["stop", "R-0001"], deps=Deps(repo_root=repo, github=github, agent=agent))
        return writes_spec(spec_text())(request)

    agent.script["requirements"] = [spec_while_stop_is_requested]

    _, out = orchestrate("start", "42")

    assert "State: stopped" in out
    assert len(of_type(repo, "safe_stop")) == 1
    assert of_type(repo, "safe_stop")[0]["data"]["reason"] == "orchestrate stop"


# Cost caps


def settings(repo: Path, text: str) -> None:
    (repo / "orchestrator" / "settings.yaml").write_text(text)


def test_reaching_the_run_cost_cap_safe_stops_before_the_next_agent_step(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    settings(repo, "cost_cap_run_usd: 0.25\n")
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
    agent.script["decompose"] = [breakdown([ticket("T1", "Store expiry")])]
    orchestrate("start", "42")

    _, out = orchestrate("approve", "R-0001", "spec")

    assert "State: stopped" in out
    assert (
        of_type(repo, "safe_stop")[0]["data"]["reason"]
        == "the Run reached its cost cap ($0.50 of $0.25)"
    )
    assert not calls(agent, "decompose")

    _, out = orchestrate("resume", "R-0001", "--cost-cap-run", "5")

    assert of_type(repo, "settings_changed")[0]["data"] == {"cost_cap_run_usd": 5.0}
    assert calls(agent, "decompose")
    assert "Approvals waiting: tickets" in out


def test_a_step_over_its_cost_cap_keeps_its_work_and_stops_before_the_next_step(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    settings(repo, "cost_cap_step_usd: 0.1\n")
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]

    _, out = orchestrate("start", "42")

    assert "State: stopped" in out
    assert of_type(repo, "cost_cap_reached")[0]["data"] == {
        "scope": "step",
        "cost_usd": 0.2,
        "cap_usd": 0.1,
    }
    _, out = orchestrate("resume", "R-0001")
    assert len(calls(agent, "requirements")) == 1
    assert "Approvals waiting: spec" in out


def test_status_reports_stopped_and_paused_runs(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    github.add_issue(43, "Another", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(BROKEN) for _ in range(3)]
    orchestrate("start", "42")
    agent.script["requirements"] = [writes_spec(spec_text())]
    orchestrate("start", "43")
    orchestrate("stop", "R-0002")

    _, out = orchestrate("status")

    assert "R-0001  paused" in out and "R-0002  stopped" in out


def test_a_lane_after_a_skipped_lane_still_runs(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    probe: Path,
) -> None:
    """Order T1, T4, T2, T3: T1 is rolled back, so T2 (blocked by T1) is skipped, and T3 (blocked
    only by T4, which merged) must still run after it."""
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
    agent.script["decompose"] = [
        breakdown(
            [
                ticket("T1", "Store expiry"),
                ticket("T2", "Expire", ("T1",)),
                ticket("T3", "Show", ("T4",)),
                ticket("T4", "Model"),
            ]
        )
    ]
    orchestrate("start", "42")
    orchestrate("approve", "R-0001", "spec")
    orchestrate("approve", "R-0001", "tickets")
    merge(repo, github, github.pr_for_head("docs/run-R-0001"))
    agent.script["implement"] = [writes({"feature.txt": f"{k}\n"}) for k in ("t1", "t4", "t3")]
    agent.script["document"] = [writes({}, docs_updated=[]) for _ in range(3)]
    orchestrate("resume", "R-0001")
    github.close_pr(github.pr_for_head("feat/100-store-expiry"))  # T1's PR rejected by a human
    orchestrate("resume", "R-0001")
    merge(repo, github, github.pr_for_head("feat/101-model"))

    orchestrate("resume", "R-0001")

    assert [e["data"]["lane"] for e in of_type(repo, "lane_skipped")] == ["T2"]
    assert github.pr_for_head("feat/103-show")
