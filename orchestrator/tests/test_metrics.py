"""Delivery metrics (#34): `orchestrate metrics` from every committed Event Log.

The Event Logs here are built by hand, and every expected value is worked out in the comments
from the spec 0002 definitions, independently of the code under test.
"""

import hashlib
import json
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from test_lane_end_to_end import lane_script, merge, on_remote, through_docs_pr

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent

T0 = datetime(2026, 10, 8, 10, 0, tzinfo=UTC)
Timeline = list[tuple[int, str, str | None, dict[str, Any]]]


def write_log(repo: Path, run: str, timeline: Timeline) -> Path:
    """A committed Event Log: (seconds after T0, type, stage, data), hash-chained per spec 0002."""
    prev = "0" * 64
    lines = []
    for seq, (second, type_, stage, data) in enumerate(timeline, start=1):
        event = {
            "schema_version": 1,
            "seq": seq,
            "ts": (T0 + timedelta(seconds=second)).isoformat(timespec="milliseconds"),
            "run": run,
            "actor": "orchestrator",
            "stage": stage,
            "type": type_,
            "data": data,
            "prev_hash": prev,
        }
        canonical = json.dumps(event, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        event["hash"] = prev = hashlib.sha256(canonical.encode()).hexdigest()
        lines.append(json.dumps(event))
    path = repo / "delivery" / "runs" / run / "events.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n")
    return path


def fail(lane: str | None = None) -> dict[str, Any]:
    return {"gate": "g", "passed": False, "problems": ["x"], **({"lane": lane} if lane else {})}


def ok(lane: str | None = None) -> dict[str, Any]:
    return {"gate": "g", "passed": True, "problems": [], **({"lane": lane} if lane else {})}


# R-0001: delivered, one Lane.
#   latency 310s. Human waits: spec approval 40→100 (60s), merge 120→300 (180s): 240s → 70s without.
#   Stage executions 3 (requirements, lanes, implement); 1 retry. 1 Lane, none rolled back.
#   Recovery: requirements failed at 10, passed at 40 → 30s. Cost 0.50 + 0.75 = $1.25.
RUN_1: Timeline = [
    (0, "run_started", None, {"issue": 42}),
    (0, "stage_started", "requirements", {}),
    (5, "agent_call", "requirements", {"cost_usd": 0.5}),
    (10, "gate_result", "requirements", fail()),
    (10, "retry", "requirements", {"gate": "spec", "attempt": 1}),
    (40, "gate_result", "requirements", ok()),
    (40, "approval_requested", "requirements", {"checkpoint": "spec"}),
    (100, "approved", "requirements", {"checkpoint": "spec", "by": "maintainer"}),
    (100, "stage_passed", "requirements", {}),
    (100, "stage_started", "lanes", {}),
    (100, "lane_started", "implement", {"lane": "T1", "issue": 100}),
    (100, "stage_started", "implement", {"lane": "T1", "issue": 100}),
    (110, "agent_call", "implement", {"cost_usd": 0.75}),
    (120, "approval_requested", "lanes", {"checkpoint": "merge:5", "pr": 5}),
    (300, "approved", "lanes", {"checkpoint": "merge:5", "by": "maintainer"}),
    (300, "lane_finished", "pr", {"lane": "T1", "outcome": "merged"}),
    (310, "run_finished", None, {"outcome": "delivered"}),
]

# R-0002: partially delivered, two parallel Lanes.
#   latency 300s. Human waits overlap: merge:7 50→150 and merge:8 80→200 make 50→200 (150s), then a
#   Safe-stop 210→250 (40s): 190s → 110s without.
#   Stage executions 3 (lanes, implement T1, implement T2); 1 retry. 2 Lanes, 1 rolled back.
#   Recovery: T1's implement gate failed at 20; T2's passing gate at 30 is another Lane's and does
#   not count; T1's passed at 60 → 40s. Cost $2.25.
RUN_2: Timeline = [
    (0, "run_started", None, {"issue": 43}),
    (0, "stage_started", "lanes", {}),
    (0, "lane_started", "implement", {"lane": "T1", "issue": 200}),
    (0, "stage_started", "implement", {"lane": "T1", "issue": 200}),
    (0, "lane_started", "implement", {"lane": "T2", "issue": 201}),
    (0, "stage_started", "implement", {"lane": "T2", "issue": 201}),
    (20, "gate_result", "implement", fail("T1")),
    (20, "retry", "implement", {"gate": "verify", "attempt": 1, "lane": "T1"}),
    (30, "gate_result", "implement", ok("T2")),
    (50, "approval_requested", "lanes", {"checkpoint": "merge:7", "pr": 7}),
    (60, "gate_result", "implement", ok("T1")),
    (80, "approval_requested", "lanes", {"checkpoint": "merge:8", "pr": 8}),
    (150, "approved", "lanes", {"checkpoint": "merge:7", "by": "maintainer"}),
    (150, "lane_finished", "pr", {"lane": "T2", "outcome": "merged"}),
    (200, "rejected", "lanes", {"checkpoint": "merge:8", "by": None}),
    (200, "lane_rolled_back", "implement", {"lane": "T1", "issue": 200, "pr": 8}),
    (200, "lane_finished", "implement", {"lane": "T1", "outcome": "rolled_back"}),
    (210, "safe_stop", None, {"reason": "orchestrate stop"}),
    (250, "resumed", None, {}),
    (260, "agent_call", "implement", {"cost_usd": 2.25}),
    (300, "run_finished", None, {"outcome": "partially delivered"}),
]

# Across both Runs: success 1 of 2 = 50%; retries 2 of 6 Stage executions = 33.3%; rollbacks 1 of
# 3 Lanes = 33.3%; MTTR (30 + 40) / 2 = 35s; latency (310 + 300) / 2 = 305s = 5m 05s, and
# (70 + 110) / 2 = 90s = 1m 30s without human wait; cost (1.25 + 2.25) / 2 = $1.75, $3.50 in all.


def metrics_md(repo: Path) -> str:
    return (repo / "delivery" / "metrics.md").read_text()


def test_metrics_are_computed_from_every_committed_event_log(
    orchestrate: Orchestrate, repo: Path
) -> None:
    write_log(repo, "R-0001", RUN_1)
    write_log(repo, "R-0002", RUN_2)

    code, out = orchestrate("metrics")

    assert code == 0, out
    md = metrics_md(repo)
    for row in (
        "| Success rate | 50% (1 of 2 Runs fully delivered) |",
        "| Retry frequency | 33.3% (2 retries in 6 Stage executions) |",
        "| Rollback frequency | 33.3% (1 of 3 Lanes rolled back) |",
        "| MTTR | 35s (2 recoveries) |",
        "| End-to-end latency | 5m 05s mean; 1m 30s excluding human wait |",
        "| Cost per Run | $1.75 mean ($3.50 in all) |",
        "| R-0001 | delivered | 5m 10s | 1m 10s | 1 | 0 | $1.25 |",
        "| R-0002 | partially delivered | 5m 00s | 1m 50s | 1 | 1 | $2.25 |",
    ):
        assert row in md, row
    assert "Success rate: 50%" in out and "delivery/metrics.md" in out


def test_a_log_that_fails_verify_is_ignored_and_the_output_says_so(
    orchestrate: Orchestrate, repo: Path
) -> None:
    write_log(repo, "R-0001", RUN_1)
    tampered = write_log(repo, "R-0002", RUN_2)
    lines = tampered.read_text().splitlines()
    event = json.loads(lines[19])
    event["data"]["cost_usd"] = 0.01  # cheaper on paper
    lines[19] = json.dumps(event)
    tampered.write_text("\n".join(lines) + "\n")

    code, out = orchestrate("metrics")

    assert code == 0, out
    assert "Ignored R-0002: event 20 was changed after it was written" in out
    md = metrics_md(repo)
    assert "| Success rate | 100% (1 of 1 Runs fully delivered) |" in md
    assert "| R-0002 | event 20 was changed after it was written |" in md
    assert "$0.01" not in md


def test_an_unfinished_run_is_listed_but_not_counted(orchestrate: Orchestrate, repo: Path) -> None:
    write_log(repo, "R-0001", RUN_1)
    write_log(repo, "R-0002", RUN_2[:10])

    orchestrate("metrics")

    md = metrics_md(repo)
    assert "| Success rate | 100% (1 of 1 Runs fully delivered) |" in md
    assert "| R-0002 | not finished |" in md


def test_no_committed_logs_yet(orchestrate: Orchestrate, repo: Path) -> None:
    code, _ = orchestrate("metrics")

    assert code == 0 and "No finished Runs yet" in metrics_md(repo)


def test_a_pause_ends_when_its_lane_is_rolled_back(orchestrate: Orchestrate, repo: Path) -> None:
    # 100s Run; T1 paused 10→70 and rolled back by the engineer (no `resumed`): 60s of human wait.
    write_log(
        repo,
        "R-0001",
        [
            (0, "run_started", None, {"issue": 42}),
            (0, "stage_started", "implement", {"lane": "T1"}),
            (10, "paused", "implement", {"reason": "x", "lane": "T1"}),
            (70, "lane_rolled_back", "implement", {"lane": "T1", "by": "maintainer"}),
            (100, "run_finished", None, {"outcome": "partially delivered"}),
        ],
    )

    orchestrate("metrics")

    assert "| R-0001 | partially delivered | 1m 40s | 40s | 0 | 1 | $0.00 |" in metrics_md(repo)


def test_close_out_regenerates_the_metrics_with_the_finished_run(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)
    pr = through_docs_pr(orchestrate, github, repo, published)
    merge(repo, github, pr)

    orchestrate("resume", "R-0001")

    md = on_remote(repo, "docs/run-R-0001-close-out", "delivery/metrics.md")
    assert "| Success rate | 100% (1 of 1 Runs fully delivered) |" in md
    assert "| R-0001 | delivered |" in md


def test_a_wait_still_open_when_the_run_finishes_counts_until_the_end(
    orchestrate: Orchestrate, repo: Path
) -> None:
    # 100s Run; an approval requested at 60 is never answered: 40s of human wait → 60s without.
    write_log(
        repo,
        "R-0001",
        [
            (0, "run_started", None, {"issue": 42}),
            (60, "approval_requested", "lanes", {"checkpoint": "merge:9", "pr": 9}),
            (100, "run_finished", None, {"outcome": "delivered"}),
        ],
    )

    orchestrate("metrics")

    assert "| R-0001 | delivered | 1m 40s | 1m 00s | 0 | 0 | $0.00 |" in metrics_md(repo)
