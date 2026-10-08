"""The orchestrator demo (#84): one scripted Run through the real `orchestrate` command.

The agent and GitHub are the test stand-ins, so it needs no API key and no network. Everything
else is real: the graph, the gates, the policy check, git worktrees and the Event Log. The Run
shows parallel Lanes and the join, a retried gate, a rollback, and a re-plan after a human edits
the ticket breakdown. `scripts/orchestrator-demo.sh` runs it and keeps what it produced.
"""

import json
import os
import shutil
from pathlib import Path
from typing import Any

from lane_fixtures import Plan
from test_design_decompose import ticket
from test_lane_end_to_end import git, merge, of_type, on_remote, writes
from test_replan import TICKETS, human_pushes, tickets_on

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent

STORE = ticket("T1", "Store an expiry time on a Link")
SHOW = ticket("T2", "Show the expiry time on the result page")
EXPIRE = ticket("T3", "Answer 410 for an expired Link", ("T1",))


def test_demo_run_fans_out_retries_rolls_back_replans_and_closes_out(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, plan: Plan
) -> None:
    # The Lane gate needs no running service here; keep machine paths out of the Event Log.
    (repo / "orchestrator" / "settings.yaml").write_text(
        "verify_command: test -f feature.txt\n"
        "app_start_command: 'true'\n"
        "app_stop_command: 'true'\n"
        "browser_check_command: 'true'\n"
    )

    # 1. Requirements, design and decompose, each approved by a human; the docs PR merged.
    #    T1 fails its verify gate once (no feature.txt), then passes; T2 never passes.
    agent.script["implement:T1"] = [
        writes({"T1.txt": "expiry column\n"}),
        writes({"feature.txt": "expiry\n", "T1.txt": "expiry column\n"}),
    ]
    agent.script["implement:T2"] = [writes({"T2.txt": "no feature\n"}) for _ in range(3)]
    plan(STORE, SHOW, EXPIRE)

    # 2. T1 and T2 fan out in parallel; T3 waits at the join for T1. T2 runs out of retries.
    _, out = orchestrate("resume", "R-0001")
    assert "State: paused" in out
    assert of_type(repo, "retry")

    # 3. A human rolls T2 back: PR closed, branch and worktree deleted, ticket back to Todo.
    code, out = orchestrate(
        "reject", "R-0001", "lane:T2", "--reason", "Out of scope for this Run; rethink the page."
    )
    assert code == 0, out
    assert of_type(repo, "lane_rolled_back")

    # 4. A human tightens T3 on main while it is still waiting: the Run re-plans and the
    #    edited breakdown needs a fresh approval before T3 starts.
    edited = tickets_on(repo, "main")
    edited[2]["acceptance"].append("HEAD on an expired Link also answers 410")
    human_pushes(
        repo, "main", {TICKETS: json.dumps(edited, indent=2) + "\n"}, "docs: tighten T3 (#42)"
    )
    _, out = orchestrate("replan", "R-0001")
    assert of_type(repo, "replanned")
    code, out = orchestrate("approve", "R-0001", "tickets")
    assert code == 0, out

    # 5. The human merges T1; T3 starts from the new main and opens its PR; the human merges it.
    merge(repo, github, lane_pr(github, 100))
    orchestrate("resume", "R-0001")
    merge(repo, github, lane_pr(github, 102))

    # 6. Release readiness and close-out: the Run is partially delivered (T2 rolled back).
    _, out = orchestrate("resume", "R-0001")
    assert "State: finished" in out
    finished = of_type(repo, "run_finished")[0]["data"]
    assert finished["outcome"] == "partially delivered"
    assert finished["rolled_back"] == ["T2"]
    code, out = orchestrate("verify", "R-0001")
    assert code == 0, out

    keep(repo)


def lane_pr(github: InMemoryGitHub, issue: int) -> int:
    """The open PR of the Lane for ticket Issue #issue (tickets are published as #100 on)."""
    return next(
        n
        for n, pr in github.prs.items()
        if pr.head.startswith(f"feat/{issue}-") and pr.state == "open"
    )


def keep(repo: Path) -> None:
    """With ORCHESTRATOR_DEMO_OUT set, save the Event Log, report and a readable timeline."""
    target = os.environ.get("ORCHESTRATOR_DEMO_OUT")
    if not target:
        return
    out = Path(target)
    out.mkdir(parents=True, exist_ok=True)
    run = repo / ".orchestrator" / "runs" / "R-0001"
    shutil.copyfile(run / "events.jsonl", out / "events.jsonl")
    report = on_remote(repo, "docs/run-R-0001-close-out", "delivery/runs/R-0001/report.md")
    (out / "report.md").write_text(report)
    (out / "timeline.txt").write_text(timeline(run / "events.jsonl"))
    git(repo, "log", "--oneline", "-1")  # the repository is still intact


SHOWN = {
    "run_started",
    "stage_passed",
    "approval_requested",
    "approved",
    "lane_started",
    "retry",
    "paused",
    "lane_rolled_back",
    "replanned",
    "invalidated",
    "pr_opened",
    "lane_finished",
    "stage_failed",
    "run_finished",
}


def timeline(path: Path) -> str:
    lines = []
    for raw in path.read_text().splitlines():
        event: dict[str, Any] = json.loads(raw)
        if event["type"] not in SHOWN:
            continue
        data = event.get("data") or {}
        detail = ", ".join(
            f"{k}={v}"
            for k, v in data.items()
            if k in {"checkpoint", "lane", "reason", "from", "outcome", "pr", "rolled_back"}
        )
        stage = event.get("stage") or ""
        lines.append(f"{event['seq']:>4}  {event['type']:<18} {stage:<18} {detail}"[:160])
    return "\n".join(lines) + "\n"
