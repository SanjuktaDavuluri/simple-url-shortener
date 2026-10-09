"""Hardening found in the first real Run, R-0001 (#62), through the `orchestrate` command."""

import json
from pathlib import Path
from typing import Any

from lane_fixtures import Plan, implemented, lane_pr
from test_design_decompose import ticket
from test_lane_end_to_end import git, lane_script, merge, on_remote, through_docs_pr, writes

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.github import Check


def events(repo: Path) -> list[dict[str, Any]]:
    path = repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()]


def of_type(repo: Path, type: str) -> list[dict[str, Any]]:
    return [e for e in events(repo) if e["type"] == type]


# 3. Close-out keeps the spec index current

INDEX = """# Specs

| # | Spec | Status | Roadmap |
|---|---|---|---|
| 0001 | [v1 core](0001-v1-core.md) | implemented | core |
"""


def push_to_main(repo: Path, files: dict[str, str]) -> None:
    for path, text in files.items():
        (repo / path).parent.mkdir(parents=True, exist_ok=True)
        (repo / path).write_text(text)
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "docs: index")
    git(repo, "push", "-q", "origin", "main")


def test_close_out_adds_the_specs_row_to_the_spec_index_as_implemented(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Any,
) -> None:
    push_to_main(repo, {"docs/specs/README.md": INDEX})
    lane_script(agent)
    merge(repo, github, through_docs_pr(orchestrate, github, repo, published))

    orchestrate("resume", "R-0001")

    index = on_remote(repo, "docs/run-R-0001-close-out", "docs/specs/README.md")
    assert "| 0001 | [v1 core](0001-v1-core.md) | implemented | core |" in index
    assert (
        "| 0003 | [Expiring links](0003-expiring-links.md) | implemented "
        "| R18: Run R-0001, Issue #42 |" in index
    )


def test_close_out_marks_an_existing_index_row_implemented(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Any,
) -> None:
    row = "| 0003 | [Expiring links](0003-expiring-links.md) | accepted | R18 (Release 2) |"
    push_to_main(repo, {"docs/specs/README.md": INDEX + row + "\n"})
    lane_script(agent)
    merge(repo, github, through_docs_pr(orchestrate, github, repo, published))

    orchestrate("resume", "R-0001")

    index = on_remote(repo, "docs/run-R-0001-close-out", "docs/specs/README.md")
    assert (
        "| 0003 | [Expiring links](0003-expiring-links.md) | implemented | R18 (Release 2) |"
        in index
    )
    assert index.count("| 0003 |") == 1


# 4. A gate command that can't run pauses the Lane; the agent isn't asked to fix the machine


def test_an_environment_failure_in_verify_pauses_at_once_and_resume_reruns_only_the_gate(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Any,
) -> None:
    ready = repo.parent / "jdk-installed"
    (repo / "orchestrator" / "settings.yaml").write_text(
        f"verify_command: test -f {ready} || "
        '{ echo "Unable to locate a Java Runtime." >&2; exit 127; }; test -f feature.txt\n'
    )
    lane_script(agent)
    merge(repo, github, published())

    _, out = orchestrate("resume", "R-0001")

    assert "State: paused" in out
    assert not of_type(repo, "retry")  # no agent retry for a machine problem
    [paused] = [e for e in of_type(repo, "paused") if e["data"].get("lane") == "T1"]
    assert "couldn't run" in paused["data"]["reason"] and "Java Runtime" in paused["data"]["reason"]
    assert implemented(agent) == ["T1"]

    ready.write_text("yes")
    orchestrate("resume", "R-0001")

    assert implemented(agent) == ["T1"]  # the gate ran again, not the agent
    assert github.pr_for_head("feat/100-store-expiry-on-a-link")


# 5. Open Lane PRs are kept up to date with main


T1, T2 = ticket("T1", "Store expiry t1"), ticket("T2", "Show expiry t2")


def test_a_waiting_lane_takes_in_main_after_a_sibling_merges(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    plan(T1, T2)
    orchestrate("resume", "R-0001")
    t1, t2 = lane_pr(github, "T1") or 0, lane_pr(github, "T2") or 0
    merge(repo, github, t1)

    orchestrate("resume", "R-0001")

    head = github.pr(t2).head
    assert on_remote(repo, head, "T1.txt") == "T1\n"  # T1's work is on T2's branch now
    subject = git(
        repo, "--git-dir", str(repo.parent / "remote.git"), "log", "-1", "--format=%s", head
    )
    assert subject.strip() == "chore: bring main into the Lane (#101)"
    assert [e["data"]["lane"] for e in of_type(repo, "pr_updated")] == ["T2"]
    merge(repo, github, t2)
    _, out = orchestrate("resume", "R-0001")
    assert "State: finished" in out  # release readiness accepts the commit: it names #101


def test_a_conflict_with_main_pauses_the_lane_naming_the_files(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    agent.script["implement:T1"] = [writes({"feature.txt": "x\n", "shared.txt": "from T1\n"})]
    agent.script["implement:T2"] = [writes({"feature.txt": "x\n", "shared.txt": "from T2\n"})]
    plan(T1, T2)
    orchestrate("resume", "R-0001")
    merge(repo, github, lane_pr(github, "T1") or 0)

    _, out = orchestrate("resume", "R-0001")

    assert "State: paused" in out
    [paused] = [e for e in of_type(repo, "paused") if e["data"].get("lane") == "T2"]
    assert "shared.txt" in paused["data"]["reason"]
    tree = repo / ".orchestrator" / "worktrees" / "R-0001-101"
    assert not git(tree, "status", "--porcelain").strip()  # the failed merge was undone

    git(tree, "merge", "-q", "origin/main", "-m", "chore: merge main (#101)", check=False)
    (tree / "shared.txt").write_text("from T1 and T2\n")
    git(tree, "add", ".")
    git(tree, "commit", "-qm", "chore: resolve shared.txt with main (#101)")
    orchestrate("resume", "R-0001")

    assert (
        on_remote(repo, github.pr(lane_pr(github, "T2") or 0).head, "shared.txt")
        == "from T1 and T2\n"
    )


def test_a_conflicting_pr_gets_no_merge_approval_until_resolved(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    """#76: the conflict check runs before the merge approval is asked for."""
    github.default_checks = (Check("Verify", "pending", ""),)
    agent.script["implement:T1"] = [writes({"feature.txt": "x\n", "shared.txt": "from T1\n"})]
    agent.script["implement:T2"] = [writes({"feature.txt": "x\n", "shared.txt": "from T2\n"})]
    plan(T1, T2)
    orchestrate("resume", "R-0001")  # both PRs open, checks still running
    t1, t2 = lane_pr(github, "T1") or 0, lane_pr(github, "T2") or 0
    opened = {e["data"]["pr"]: e["data"]["sha"] for e in of_type(repo, "pr_opened")}
    github.set_checks(t1, opened[t1], Check("Verify", "success", ""))
    orchestrate("resume", "R-0001")
    merge(repo, github, t1)
    github.set_checks(t2, opened[t2], Check("Verify", "success", ""))

    orchestrate("resume", "R-0001")

    def asked(pr: int) -> int:
        return len(
            [
                e
                for e in of_type(repo, "approval_requested")
                if e["data"]["checkpoint"] == f"merge:{pr}"
            ]
        )

    [paused] = [e for e in of_type(repo, "paused") if e["data"].get("lane") == "T2"]
    assert asked(t2) == 0  # a PR that can't merge is never offered for merging
    reason = paused["data"]["reason"]
    assert ".orchestrator/worktrees/R-0001-101" in reason and str(repo) not in reason

    tree = repo / ".orchestrator" / "worktrees" / "R-0001-101"
    git(tree, "merge", "-q", "origin/main", "-m", "chore: merge main (#101)", check=False)
    (tree / "shared.txt").write_text("from T1 and T2\n")
    git(tree, "add", ".")
    git(tree, "commit", "-qm", "chore: resolve shared.txt with main (#101)")
    orchestrate("resume", "R-0001")  # pushes the resolution; its checks run again
    sha = [e["data"]["sha"] for e in of_type(repo, "pr_updated") if e["data"]["pr"] == t2][-1]
    github.set_checks(t2, sha, Check("Verify", "success", ""))
    orchestrate("resume", "R-0001")

    assert asked(t2) == 1


def test_sibling_lanes_appending_to_the_shared_docs_merge_without_a_pause(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    """#77: a conflict only in the shared documents is merged row by row; the PR takes main in."""
    plan_path = "docs/plans/0001-integration-testing.md"
    table = "| Ticket | Check | Status |\n|---|---|---|\n| #1 | existing | ☐ |\n"
    (repo / "docs" / "plans").mkdir(parents=True, exist_ok=True)
    (repo / plan_path).write_text(table)
    git(repo, "add", plan_path)
    git(repo, "commit", "-qm", "docs: plan table")
    git(repo, "push", "-q", "origin", "main")
    agent.script["implement:T1"] = [
        writes({"feature.txt": "x\n", plan_path: table + "| #100 | from T1 | ☐ |\n"})
    ]
    agent.script["implement:T2"] = [
        writes({"feature.txt": "x\n", plan_path: table + "| #101 | from T2 | ☐ |\n"})
    ]
    plan(T1, T2)
    orchestrate("resume", "R-0001")
    merge(repo, github, lane_pr(github, "T1") or 0)

    orchestrate("resume", "R-0001")

    assert not [e for e in of_type(repo, "paused") if e["data"].get("lane") == "T2"]
    [merged] = of_type(repo, "conflict_merged_by_rows")
    assert merged["data"]["lane"] == "T2" and merged["data"]["files"] == [plan_path]
    on_t2 = on_remote(repo, github.pr(lane_pr(github, "T2") or 0).head, plan_path)
    assert "| #100 | from T1 |" in on_t2 and "| #101 | from T2 |" in on_t2
    assert "<<<<<<<" not in on_t2
