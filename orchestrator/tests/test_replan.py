"""Re-plan (#33, ADR 0011): content-hash lineage, invalidation, follow-ups and spec amendments."""

import json
from pathlib import Path
from typing import Any

import pytest
from lane_fixtures import Plan, implemented, lane_pr
from test_design_decompose import breakdown, ticket, writes_adrs
from test_lane_end_to_end import git, merge, of_type, on_remote, stage_state, writes
from test_requirements_stage import SPEC_PATH, spec_text, writes_spec

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator import lanes
from orchestrator.agent import StepRequest, StepResult
from orchestrator.hashing import content_hash

TICKETS = "delivery/runs/R-0001/tickets.json"
T1, T2 = ticket("T1", "Store expiry t1"), ticket("T2", "Show expiry t2")
AMENDED = spec_text().replace("Links can expire.", "Links can expire, and expired links say so.")


def human_pushes(repo: Path, branch: str, files: dict[str, str], message: str) -> None:
    """A human changes files on a branch on GitHub (a merged PR, or a push to the Run's branch)."""
    clone = repo.parent / f"human-{branch.replace('/', '-')}"
    if not clone.exists():
        git(repo.parent, "clone", "-q", str(repo.parent / "remote.git"), str(clone))
        git(clone, "config", "user.email", "human@example.com")
        git(clone, "config", "user.name", "Human")
    git(clone, "fetch", "-q", "origin")
    git(clone, "checkout", "-q", "-B", branch, f"origin/{branch}")
    for path, text in files.items():
        (clone / path).parent.mkdir(parents=True, exist_ok=True)
        (clone / path).write_text(text)
    git(clone, "commit", "-qam", message)
    git(clone, "push", "-q", "origin", branch)


def tickets_on(repo: Path, ref: str) -> list[dict[str, Any]]:
    return list(json.loads(on_remote(repo, ref, TICKETS)))


def replanned(repo: Path) -> list[dict[str, Any]]:
    return [e["data"] for e in of_type(repo, "replanned")]


def calls(agent: ScriptedAgent, stage: str) -> int:
    return len([r for r in agent.requests if r.stage == stage])


def again(agent: ScriptedAgent, *tickets: dict[str, Any]) -> None:
    """What design and decompose answer when they run again after a re-plan."""
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Still nothing new.")]
    agent.script["decompose"] = [breakdown(list(tickets))]


def stage_passed(repo: Path, stage: str) -> dict[str, Any]:
    passed: dict[str, Any] = [
        e["data"] for e in of_type(repo, "stage_passed") if e["stage"] == stage
    ][-1]
    return passed


# Lineage: every Stage records the hashes of its inputs and approvals


def test_each_stage_records_the_hashes_of_its_inputs_and_the_approvals_it_used(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path, plan: Plan
) -> None:
    plan(T1)

    issue = github.get_issue(42)
    spec_hash = content_hash(on_remote(repo, "main", SPEC_PATH))
    tickets_hash = content_hash(on_remote(repo, "main", TICKETS))
    assert stage_passed(repo, "requirements")["inputs"] == {
        "issue": content_hash(f"{issue.title}\n{issue.body}")
    }
    assert stage_passed(repo, "design")["inputs"] == {"spec": spec_hash}
    assert stage_passed(repo, "decompose")["inputs"] == {
        "spec": spec_hash,
        "adrs": content_hash(""),
    }
    assert {"checkpoint": "spec", "hash": spec_hash} in stage_passed(repo, "requirements")[
        "approvals"
    ]
    assert {"checkpoint": "tickets", "hash": tickets_hash} in stage_passed(repo, "decompose")[
        "approvals"
    ]
    lanes = [e["data"] for e in of_type(repo, "stage_started") if e["stage"] == "lanes"][-1]
    assert lanes["inputs"]["tickets"] == tickets_hash


# Detection: on `orchestrate replan` and at every Stage boundary


def test_replan_with_nothing_changed_says_so(orchestrate: Orchestrate, plan: Plan) -> None:
    plan(T1)

    code, out = orchestrate("replan", "R-0001")

    assert code == 0 and "No inputs changed" in out


def test_a_whitespace_only_edit_is_not_a_change(
    orchestrate: Orchestrate, repo: Path, plan: Plan
) -> None:
    plan(T1)
    human_pushes(repo, "main", {SPEC_PATH: spec_text() + "\n\n   \n"}, "docs: tidy the spec")

    _, out = orchestrate("replan", "R-0001")

    assert "No inputs changed" in out and not replanned(repo)


def test_a_spec_change_invalidates_only_the_stages_downstream_of_the_spec(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1)
    orchestrate("resume", "R-0001")  # T1 reaches its PR
    old = content_hash(on_remote(repo, "main", SPEC_PATH))
    human_pushes(repo, "main", {SPEC_PATH: AMENDED}, "docs: clarify expiry (#42)")
    again(agent, T1)

    code, out = orchestrate("replan", "R-0001")

    assert code == 0, out
    [data] = replanned(repo)
    assert data["changes"] == [{"artifact": "spec", "old": old, "new": content_hash(AMENDED)}]
    assert data["invalidated"] == ["design", "decompose", "lanes"]
    assert data["from"] == "design"
    invalidated = [e["data"]["stage"] for e in of_type(repo, "invalidated")]
    assert invalidated == ["design", "decompose", "lanes"]
    assert calls(agent, "requirements") == 1  # upstream of the spec: kept
    assert calls(agent, "design") == 2 and calls(agent, "decompose") == 2
    assert "Approvals waiting: tickets" in out
    assert stage_passed(repo, "design")["inputs"] == {"spec": content_hash(AMENDED)}


def test_a_change_is_detected_at_the_next_stage_boundary_without_replan(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
    agent.script["decompose"] = [breakdown([T1])]
    orchestrate("start", "42")
    orchestrate("approve", "R-0001", "spec")  # design and decompose run; tickets wait
    human_pushes(repo, "docs/run-R-0001", {SPEC_PATH: AMENDED}, "docs: clarify expiry (#42)")
    again(agent, T1)

    orchestrate("approve", "R-0001", "tickets")  # publish, then the lanes boundary notices

    [data] = replanned(repo)
    assert data["from"] == "design" and data["changes"][0]["artifact"] == "spec"
    assert calls(agent, "design") == 2


def test_an_issue_change_reruns_requirements_and_returns_to_spec_approval(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1)
    github.add_issue(42, "Expiring links", "Roadmap R18. Expired links should say so.")
    agent.script["requirements"] = [writes_spec(AMENDED)]

    _, out = orchestrate("replan", "R-0001")

    [data] = replanned(repo)
    assert data["from"] == "requirements" and data["changes"][0]["artifact"] == "issue"
    assert calls(agent, "requirements") == 2
    assert "Approvals waiting: spec" in out


# A changed ticket breakdown returns to the tickets Approval Checkpoint


def test_an_edited_breakdown_returns_to_the_tickets_approval_and_only_changed_lanes_rerun(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1, T2)
    orchestrate("resume", "R-0001")  # T1 and T2 reach their PRs
    t2_pr = lane_pr(github, "T2")
    edited = tickets_on(repo, "main")
    edited[1]["acceptance"] = ["Expiry shows on the result page"]
    edited.append(ticket("T3", "Expire links t3", ("T1",)))
    human_pushes(
        repo, "main", {TICKETS: json.dumps(edited, indent=2) + "\n"}, "docs: rework tickets (#42)"
    )

    _, out = orchestrate("replan", "R-0001")

    [data] = replanned(repo)
    assert data["from"] == "decompose" and data["changes"][0]["artifact"] == "tickets"
    assert "Approvals waiting: tickets" in out
    assert calls(agent, "decompose") == 1  # the human's breakdown is approved, not redrafted

    agent.script["implement:T2"] = [writes({"feature.txt": "x\n", "T2.txt": "T2 v2\n"})]
    agent.script["document:T2"] = [writes({}, docs_updated=[])]
    code, out = orchestrate("approve", "R-0001", "tickets")

    assert code == 0, out
    assert github.pr(t2_pr or 0).state == "closed"  # the changed, unmerged Lane is redone
    lane_t2 = [e["data"] for e in of_type(repo, "invalidated") if e["data"].get("lane") == "T2"]
    assert lane_t2 and lane_t2[0]["reason"] == "its ticket changed"
    assert implemented(agent).count("T2") == 2
    assert implemented(agent).count("T1") == 1  # unchanged: kept as it was
    assert github.pr(lane_pr(github, "T1") or 0).state == "open"
    assert any(i.title == "Expire links t3" for i in github.issues.values())  # T3 published


def test_a_finished_run_is_never_replanned(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1)
    orchestrate("resume", "R-0001")
    t1_pr = lane_pr(github, "T1") or 0
    merge(repo, github, t1_pr)
    orchestrate("resume", "R-0001")  # T1 merged; the Run closes out
    edited = tickets_on(repo, "main")
    edited[0]["acceptance"].append("Expired links return 410")

    human_pushes(
        repo, "main", {TICKETS: json.dumps(edited, indent=2) + "\n"}, "docs: extend T1 (#42)"
    )
    _, out = orchestrate("replan", "R-0001")

    assert "finished" in out and not replanned(repo)  # a finished Run is never re-planned


def test_a_change_to_a_merged_ticket_in_a_live_run_creates_a_follow_up_ticket(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1, T2)
    orchestrate("resume", "R-0001")
    t1_pr = lane_pr(github, "T1") or 0
    merge(repo, github, t1_pr)
    orchestrate("resume", "R-0001")  # T1 merged; T2 still waits for its merge
    edited = tickets_on(repo, "main")
    edited[0]["acceptance"].append("Expired links return 410")
    human_pushes(
        repo, "main", {TICKETS: json.dumps(edited, indent=2) + "\n"}, "docs: extend T1 (#42)"
    )
    orchestrate("replan", "R-0001")
    agent.script["implement:T1-f1"] = [writes({"feature.txt": "x\n", "T1f.txt": "410\n"})]
    agent.script["document:T1-f1"] = [writes({}, docs_updated=[])]

    orchestrate("approve", "R-0001", "tickets")

    [follow_up] = of_type(repo, "follow_up_created")
    assert follow_up["data"]["key"] == "T1-f1" and follow_up["data"]["follows"] == 100
    issue = github.get_issue(follow_up["data"]["issue"])
    assert issue.title.startswith("Follow-up to #100") and "Expired links return 410" in issue.body
    assert github.issue_meta[issue.number]["milestone"] == "Release 2: Orchestrated delivery"
    assert github.pr(t1_pr).state == "merged"  # merged work is never reopened or rewritten
    assert "T1-f1" in implemented(agent) and implemented(agent).count("T1") == 1


def test_a_lane_whose_pr_merges_during_a_replan_is_followed_up_not_redone(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1, T2)
    orchestrate("resume", "R-0001")  # T1 and T2 wait for their merges
    t1_pr = lane_pr(github, "T1") or 0
    edited = tickets_on(repo, "main")
    edited[0]["acceptance"].append("Expired links return 410")
    human_pushes(
        repo, "main", {TICKETS: json.dumps(edited, indent=2) + "\n"}, "docs: extend T1 (#42)"
    )
    orchestrate("replan", "R-0001")
    merge(repo, github, t1_pr)  # merged while the Run waits for the tickets approval
    agent.script["implement:T1-f1"] = [writes({"feature.txt": "x\n", "T1f.txt": "410\n"})]
    agent.script["document:T1-f1"] = [writes({}, docs_updated=[])]

    orchestrate("approve", "R-0001", "tickets")

    assert implemented(agent).count("T1") == 1  # never rebuilt on top of its own merge
    assert github.pr(t1_pr).state == "merged"
    assert {"lane": "T1", "outcome": "merged"} in [
        e["data"] for e in of_type(repo, "lane_finished")
    ]
    assert [e["data"]["follows"] for e in of_type(repo, "follow_up_created")] == [100]


def test_a_lane_redone_before_its_pr_merged_is_recognised_as_merged_when_it_would_start(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    plan: Plan,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A Run re-planned before this check existed already holds the merged Lane as pending."""
    plan(T1, T2)
    orchestrate("resume", "R-0001")
    t1_pr = lane_pr(github, "T1") or 0
    edited = tickets_on(repo, "main")
    edited[0]["acceptance"].append("Expired links return 410")
    human_pushes(
        repo, "main", {TICKETS: json.dumps(edited, indent=2) + "\n"}, "docs: extend T1 (#42)"
    )
    orchestrate("replan", "R-0001")
    merge(repo, github, t1_pr)
    reconcile = lanes.Begin.reconcile

    def without_the_merge_check(self: lanes.Begin, state: Any) -> Any:
        with monkeypatch.context() as patched:
            patched.setattr(lanes, "merged_on_github", lambda ctx, pr: False)
            return reconcile(self, state)

    monkeypatch.setattr(lanes.Begin, "reconcile", without_the_merge_check)

    orchestrate("approve", "R-0001", "tickets")

    assert implemented(agent).count("T1") == 1
    assert {"lane": "T1", "outcome": "merged"} in [
        e["data"] for e in of_type(repo, "lane_finished")
    ]


# Spec amendments: agents propose, humans approve, and the change reaches main through a PR


def amends(text: str, reason: str) -> Any:
    def step(request: StepRequest) -> StepResult:
        return StepResult(output={"spec_amendment": {"text": text, "reason": reason}})

    return step


def test_an_agent_that_finds_a_spec_gap_raises_an_amendment_and_waits_for_approval(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    agent.script["implement:T1"] = [
        amends(AMENDED, "The spec doesn't say what an expired link shows.")
    ]

    plan(T1)
    _, out = orchestrate("resume", "R-0001")

    assert "Approvals waiting: amendment-1" in out
    assert on_remote(repo, "docs/run-R-0001-amendment-1", SPEC_PATH) == AMENDED
    assert on_remote(repo, "main", SPEC_PATH) == spec_text()  # nothing approved has changed
    request = next(
        c for c in github.comments(42) if "amendment-1" in c.body and "Approval needed" in c.body
    )
    assert "expired link shows" in request.body
    [raised] = of_type(repo, "amendment_raised")
    assert raised["data"]["lane"] == "T1" and raised["data"]["hash"] == content_hash(AMENDED)


def test_an_approved_amendment_reaches_main_through_a_pr_and_its_merge_replans(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    agent.script["implement:T1"] = [
        amends(AMENDED, "The spec doesn't say what an expired link shows."),
        writes({"feature.txt": "x\n", "T1.txt": "T1\n"}),
    ]
    plan(T1)
    orchestrate("resume", "R-0001")

    code, out = orchestrate("approve", "R-0001", "amendment-1")

    assert code == 0, out
    pr = github.pr(github.pr_for_head("docs/run-R-0001-amendment-1"))
    assert pr.base == "main" and "amendment" in pr.title.lower() and "#42" in pr.title
    again(agent, T1)
    merge(repo, github, pr.number)

    orchestrate("resume", "R-0001")

    [data] = replanned(repo)
    assert data["from"] == "design" and data["changes"][0]["new"] == content_hash(AMENDED)
    orchestrate("approve", "R-0001", "tickets")
    second = [r for r in agent.requests if r.stage == "implement"][-1]
    assert (second.workspace / SPEC_PATH).read_text() == AMENDED  # the Lane continues on it


def test_a_rejected_amendment_goes_back_to_implement_with_the_reason(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    agent.script["implement:T1"] = [
        amends(AMENDED, "Unclear."),
        writes({"feature.txt": "x\n", "T1.txt": "T1\n"}),
    ]
    plan(T1)
    orchestrate("resume", "R-0001")

    orchestrate("reject", "R-0001", "amendment-1", "--reason", "The spec already covers it.")

    feedback = [r for r in agent.requests if r.stage == "implement"][-1].context["feedback"]
    assert feedback == "amendment-1 rejected: The spec already covers it."
    assert lane_pr(github, "T1")


def test_an_agent_may_not_edit_the_spec_while_implementing(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    agent.script["implement:T1"] = [
        writes({"feature.txt": "x\n", SPEC_PATH: AMENDED}),
        writes({"feature.txt": "x\n", "T1.txt": "T1\n", SPEC_PATH: spec_text()}),
    ]
    plan(T1)

    orchestrate("resume", "R-0001")

    gate = [e for e in of_type(repo, "gate_result") if e["data"]["gate"] == "policy"]
    assert gate and SPEC_PATH in gate[0]["data"]["problems"][0]
    assert on_remote(repo, lane_branch_of(github, "T1"), SPEC_PATH) == spec_text()


def lane_branch_of(github: InMemoryGitHub, key: str) -> str:
    return github.pr(lane_pr(github, key) or 0).head


def test_status_shows_invalidated_stages_until_they_run_again(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1)
    human_pushes(repo, "main", {SPEC_PATH: AMENDED}, "docs: clarify expiry (#42)")
    again(agent, T1)

    _, out = orchestrate("replan", "R-0001")

    assert stage_state(out, "requirements") == "passed"
    assert stage_state(out, "decompose") == "running"  # waiting for the tickets approval
    assert stage_state(out, "lanes") == "invalidated"


def test_a_documents_pr_closed_by_a_replan_is_not_held_against_release_readiness(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, probe: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
    agent.script["decompose"] = [breakdown([T1])]
    agent.script["implement:T1"] = [writes({"feature.txt": "x\n", "T1.txt": "T1\n"})]
    agent.script["document:T1"] = [writes({}, docs_updated=[])]
    orchestrate("start", "42")
    orchestrate("approve", "R-0001", "spec")
    orchestrate("approve", "R-0001", "tickets")
    first_docs = github.pr_for_head("docs/run-R-0001")
    human_pushes(repo, "docs/run-R-0001", {SPEC_PATH: AMENDED}, "docs: clarify expiry (#42)")
    again(agent, T1)

    orchestrate("replan", "R-0001")
    orchestrate("approve", "R-0001", "tickets")

    assert github.pr(first_docs).state == "closed"
    second_docs = max(n for n, pr in github.prs.items() if pr.head == "docs/run-R-0001")
    merge(repo, github, second_docs)
    orchestrate("resume", "R-0001")
    merge(repo, github, lane_pr(github, "T1") or 0)
    _, out = orchestrate("resume", "R-0001")

    assert "State: finished" in out, out


def test_several_changes_restart_from_the_earliest_stage_that_consumes_one(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1)
    human_pushes(repo, "main", {SPEC_PATH: AMENDED}, "docs: clarify expiry (#42)")
    github.add_issue(42, "Expiring links", "Roadmap R18. Expired links should say so.")
    agent.script["requirements"] = [writes_spec(AMENDED)]

    orchestrate("replan", "R-0001")

    [data] = replanned(repo)
    assert {c["artifact"] for c in data["changes"]} == {"issue", "spec"}
    assert data["from"] == "requirements"
    assert data["invalidated"] == ["requirements", "design", "decompose", "lanes"]
