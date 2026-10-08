"""Parallel Lanes and the join (#30, ADR 0020), through the `orchestrate` command."""

import threading
from collections.abc import Callable
from pathlib import Path

from lane_fixtures import Plan, implemented, lane_pr
from test_design_decompose import ticket
from test_lane_end_to_end import events, merge, of_type, stage_state, writes

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.agent import StepRequest, StepResult

T1, T2 = ticket("T1", "Store expiry t1"), ticket("T2", "Show expiry t2")
T3 = ticket("T3", "Expire links t3", ("T1", "T2"))


# A Lane starts only when all its blockers have merged


def test_independent_lanes_start_together_and_their_dependent_waits_for_both_merges(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1, T2, T3)

    _, out = orchestrate("resume", "R-0001")

    t1, t2 = lane_pr(github, "T1"), lane_pr(github, "T2")
    assert t1 and t2
    assert sorted(implemented(agent)) == ["T1", "T2"]
    assert f"merge:{t1}" in out and f"merge:{t2}" in out  # one combined wait lists both PRs

    merge(repo, github, t1)
    orchestrate("resume", "R-0001")
    assert "T3" not in implemented(agent)  # T2 has not merged yet
    assert not (repo / ".orchestrator" / "worktrees" / "R-0001-102").exists()

    merge(repo, github, t2)
    orchestrate("resume", "R-0001")

    assert implemented(agent).count("T3") == 1
    t3_tree = next(
        r.workspace for r in agent.requests if r.context.get("ticket", {}).get("key") == "T3"
    )
    assert (t3_tree / "T1.txt").exists() and (t3_tree / "T2.txt").exists()  # branched after both
    assert lane_pr(github, "T3")


def test_independent_lanes_do_their_agent_work_at_the_same_time(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, plan: Plan
) -> None:
    both_working = threading.Barrier(2, timeout=10)

    def meets_the_other_lane(key: str) -> Callable[[StepRequest], StepResult]:
        def step(request: StepRequest) -> StepResult:
            both_working.wait()  # breaks, failing the Run, unless the other Lane is mid-step too
            return writes({"feature.txt": "x\n", f"{key}.txt": key})(request)

        return step

    agent.script["implement:T1"] = [meets_the_other_lane("T1")]
    agent.script["implement:T2"] = [meets_the_other_lane("T2")]

    plan(T1, T2, T3)
    code, out = orchestrate("resume", "R-0001")

    assert code == 0, out
    assert not both_working.broken
    assert lane_pr(github, "T1") and lane_pr(github, "T2")


# No more than max_parallel_lanes Lanes at once


def test_no_more_than_max_parallel_lanes_run_at_once(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    lanes = [ticket(f"T{n}", f"Lane t{n}") for n in (1, 2, 3)]
    plan(*lanes, settings="max_parallel_lanes: 2\n")

    orchestrate("resume", "R-0001")

    assert sorted(implemented(agent)) == ["T1", "T2"]
    assert lane_pr(github, "T3") is None
    orchestrate("resume", "R-0001")
    assert "T3" not in implemented(agent)  # still two in flight, both waiting for a merge

    merge(repo, github, lane_pr(github, "T2") or 0)
    orchestrate("resume", "R-0001")

    assert implemented(agent)[-1] == "T3"  # the freed slot goes to the next ready Lane
    assert lane_pr(github, "T3")


def test_a_limit_of_one_runs_the_lanes_one_after_another(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1, T2, settings="max_parallel_lanes: 1\n")

    orchestrate("resume", "R-0001")

    assert implemented(agent) == ["T1"]
    merge(repo, github, lane_pr(github, "T1") or 0)
    orchestrate("resume", "R-0001")
    assert implemented(agent) == ["T1", "T2"]


def test_lanes_in_flight_never_exceed_the_limit_according_to_the_event_log(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    lanes = [ticket(f"T{n}", f"Lane t{n}") for n in (1, 2, 3, 4)]
    plan(*lanes, settings="max_parallel_lanes: 2\n")
    orchestrate("resume", "R-0001")
    for key in ("T1", "T2", "T3", "T4"):
        merge(repo, github, lane_pr(github, key) or 0)
        orchestrate("resume", "R-0001")

    in_flight: set[str] = set()
    most = 0
    for e in [e for e in events(repo) if e["type"] in ("lane_started", "lane_finished")]:
        (in_flight.add if e["type"] == "lane_started" else in_flight.discard)(e["data"]["lane"])
        most = max(most, len(in_flight))
    assert most == 2
    assert len(of_type(repo, "lane_finished")) == 4


# The join: release readiness waits for every Lane


def test_release_readiness_starts_only_after_every_lane_has_merged(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1, T2, T3)
    orchestrate("resume", "R-0001")
    merge(repo, github, lane_pr(github, "T1") or 0)
    merge(repo, github, lane_pr(github, "T2") or 0)
    orchestrate("resume", "R-0001")

    _, out = orchestrate("status", "R-0001")
    assert stage_state(out, "release_readiness") == "pending"

    merge(repo, github, lane_pr(github, "T3") or 0)
    _, out = orchestrate("resume", "R-0001")

    assert "State: finished" in out
    log = events(repo)
    readiness = next(
        i
        for i, e in enumerate(log)
        if e["type"] == "stage_started" and e["stage"] == "release_readiness"
    )
    merges = [
        i
        for i, e in enumerate(log)
        if e["type"] == "approved" and e["data"]["checkpoint"].startswith("merge:")
    ]
    assert len(merges) == 4 and max(merges) < readiness  # the docs PR and every Lane's PR


def test_a_paused_lane_does_not_hold_back_an_independent_lane(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    agent.script["implement:T1"] = [writes({"nothing.txt": "x"}) for _ in range(3)]

    plan(T1, T2, T3)
    _, out = orchestrate("resume", "R-0001")

    assert "State: paused" in out
    assert lane_pr(github, "T1") is None
    assert lane_pr(github, "T2")  # T2 reached its PR while T1 was failing

    code, out = orchestrate("reject", "R-0001", "lane:T1", "--reason", "Rethink.")

    assert code == 0, out
    [skipped] = of_type(repo, "lane_skipped")
    assert skipped["data"]["lane"] == "T3"
    merge(repo, github, lane_pr(github, "T2") or 0)
    _, out = orchestrate("resume", "R-0001")
    assert "State: finished" in out
