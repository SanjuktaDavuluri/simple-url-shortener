"""Walking skeleton (#26): `orchestrate start`, `status` and `verify` through the CLI seam."""

import json
import subprocess
from pathlib import Path
from typing import Any

from conftest import Orchestrate
from fakes import InMemoryGitHub


def events(repo: Path, run: str) -> list[dict[str, Any]]:
    path = repo / ".orchestrator" / "runs" / run / "events.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()]


def test_start_creates_a_run_that_passes_intake(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Links should be able to expire.")

    code, out = orchestrate("start", "42")

    assert code == 0
    assert "R-0001" in out
    log = events(repo, "R-0001")
    assert [(e["stage"], e["type"]) for e in log[:4]] == [
        (None, "run_started"),
        ("intake", "stage_started"),
        ("intake", "gate_result"),
        ("intake", "stage_passed"),
    ]
    assert [e["seq"] for e in log] == list(range(1, len(log) + 1))
    assert all(e["schema_version"] == 1 and e["run"] == "R-0001" for e in log)
    assert log[0]["actor"] == "engineer"
    assert log[0]["data"]["issue"] == 42


def test_events_form_a_hash_chain(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(42, "Expiring links")
    orchestrate("start", "42")

    log = events(repo, "R-0001")

    assert log[0]["prev_hash"] == "0" * 64
    assert [e["prev_hash"] for e in log[1:]] == [e["hash"] for e in log[:-1]]
    assert len({e["hash"] for e in log}) == len(log)


def test_intake_records_the_issue_with_whitespace_insensitive_content_hashes(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(1, "Title", "Body text", labels=("ready-for-agent",))
    github.add_issue(2, "Title  ", "Body text   \r\n\n", labels=("ready-for-agent",))
    orchestrate("start", "1")
    orchestrate("start", "2")

    first = events(repo, "R-0001")[3]["data"]["artifacts"]["issue"]
    second = events(repo, "R-0002")[3]["data"]["artifacts"]["issue"]

    assert first["title"] == "Title"
    assert first["labels"] == ["ready-for-agent"]
    assert first["hashes"] == second["hashes"]
    assert set(first["hashes"]) == {"title", "body", "labels"}


def test_intake_links_the_roadmap_item_named_in_the_issue(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(7, "Build the orchestrator", "Delivers roadmap item R18.")
    github.add_issue(8, "Something else", "Mentions R99, which is not on the roadmap.")
    orchestrate("start", "7")
    orchestrate("start", "8")

    assert events(repo, "R-0001")[3]["data"]["roadmap_item"] == "R18"
    assert events(repo, "R-0002")[3]["data"]["roadmap_item"] is None


def test_start_refuses_an_issue_that_already_has_an_active_run(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(42, "Expiring links")
    orchestrate("start", "42")

    code, out = orchestrate("start", "42")

    assert code != 0
    assert "R-0001" in out and "already" in out
    assert not (repo / ".orchestrator" / "runs" / "R-0002").exists()


def test_start_fails_cleanly_for_an_unknown_issue(orchestrate: Orchestrate, repo: Path) -> None:
    code, out = orchestrate("start", "404")

    assert code != 0
    assert "#404" in out
    assert not (repo / ".orchestrator" / "runs").exists() or not any(
        (repo / ".orchestrator" / "runs").iterdir()
    )


def test_status_shows_each_stage(orchestrate: Orchestrate, github: InMemoryGitHub) -> None:
    github.add_issue(42, "Expiring links")
    orchestrate("start", "42")

    code, out = orchestrate("status", "R-0001")

    assert code == 0
    assert "#42" in out
    lines = {line.split()[0]: line.split()[1] for line in out.splitlines() if line.startswith("  ")}
    assert lines == {
        "intake": "passed",
        "requirements": "running",
        "design": "pending",
        "decompose": "pending",
        "lanes": "pending",
        "release_readiness": "pending",
        "close_out": "pending",
    }


def test_status_without_a_run_lists_every_run(
    orchestrate: Orchestrate, github: InMemoryGitHub
) -> None:
    github.add_issue(1, "One")
    github.add_issue(2, "Two")
    orchestrate("start", "1")
    orchestrate("start", "2")

    code, out = orchestrate("status")

    assert code == 0
    assert "R-0001" in out and "#1" in out
    assert "R-0002" in out and "#2" in out


def test_verify_passes_an_intact_log(orchestrate: Orchestrate, github: InMemoryGitHub) -> None:
    github.add_issue(42, "Expiring links")
    orchestrate("start", "42")

    code, out = orchestrate("verify", "R-0001")

    assert code == 0
    assert "R-0001" in out and "intact" in out


def test_verify_names_the_first_edited_event(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(42, "Expiring links")
    orchestrate("start", "42")
    path = repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl"
    lines = path.read_text().splitlines()
    tampered = json.loads(lines[1])
    tampered["actor"] = "someone-else"
    lines[1] = json.dumps(tampered)
    path.write_text("\n".join(lines) + "\n")

    code, out = orchestrate("verify", "R-0001")

    assert code == 1
    assert "event 2" in out


def test_verify_detects_a_removed_event(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(42, "Expiring links")
    orchestrate("start", "42")
    path = repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl"
    lines = path.read_text().splitlines()
    del lines[1]
    path.write_text("\n".join(lines) + "\n")

    code, out = orchestrate("verify", "R-0001")

    assert code == 1
    assert "event 2" in out


def test_verify_all_checks_local_and_committed_runs(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(1, "One")
    github.add_issue(2, "Two")
    orchestrate("start", "1")
    orchestrate("start", "2")
    committed = repo / "delivery" / "runs" / "R-0002"
    committed.mkdir(parents=True)
    local = repo / ".orchestrator" / "runs" / "R-0002" / "events.jsonl"
    (committed / "events.jsonl").write_text(local.read_text().replace('"Two"', '"Changed"'))
    local.unlink()
    local.parent.rmdir()

    code, out = orchestrate("verify", "--all")

    assert code == 1
    assert "R-0001" in out and "intact" in out
    assert "R-0002" in out and "broken" in out


def test_run_records_the_settings_it_used(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    (repo / "orchestrator").mkdir()
    (repo / "orchestrator" / "settings.yaml").write_text("max_parallel_lanes: 3\n")
    github.add_issue(42, "Expiring links")

    orchestrate("start", "42")

    settings = events(repo, "R-0001")[0]["data"]["settings"]
    assert settings == {
        "max_retries": 2,
        "max_parallel_lanes": 3,
        "cost_cap_step_usd": 5.0,
        "cost_cap_run_usd": 50.0,
        "model": "claude-opus-5-5",
        "board_owner": "",
        "board_number": 0,
    }


def test_run_state_stays_out_of_the_working_tree(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path
) -> None:
    github.add_issue(42, "Expiring links")

    orchestrate("start", "42")

    written = {p.relative_to(repo).parts[0] for p in repo.rglob("*") if p.is_file()}
    assert written - {".git", "docs"} == {".orchestrator"}
    assert not subprocess.run(
        ["git", "status", "--porcelain", "--ignored=no"], cwd=repo, capture_output=True, text=True
    ).stdout.replace("?? .orchestrator/\n", "")


def test_unknown_run_is_reported(orchestrate: Orchestrate) -> None:
    code, out = orchestrate("status", "R-0099")

    assert code != 0
    assert "R-0099" in out
