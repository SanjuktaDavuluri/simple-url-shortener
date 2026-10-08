"""Fixtures shared by the Lane tests: settings probes, a Run taken to one published ticket, and a
Run taken to a plan of several tickets with its documents on main."""

from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from test_design_decompose import breakdown, ticket, writes_adrs
from test_lane_end_to_end import merge, writes
from test_requirements_stage import spec_text, writes_spec

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent

TICKET = ticket("T1", "Store expiry on a Link")


@pytest.fixture
def probe(repo: Path) -> Path:
    """Settings whose commands record what they were run with, instead of building the service."""
    log = repo.parent / "probe.log"
    (repo / "orchestrator").mkdir(exist_ok=True)
    (repo / "orchestrator" / "settings.yaml").write_text(
        "verify_command: test -f feature.txt\n"
        f'app_start_command: echo "start $PORT $DATA_DIR $(pwd)" >> {log}\n'
        f'app_stop_command: echo "stop $PORT $DATA_DIR" >> {log}\n'
        f'browser_check_command: echo "check $BASE_URL" >> {log}\n'
    )
    return log


@pytest.fixture
def published(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, probe: Path
) -> Callable[[], int]:
    """Takes Issue #42 to one published ticket; returns the docs PR number."""
    github.add_issue(42, "Expiring links", "Links should be able to expire. Roadmap R18.")

    def run() -> int:
        agent.script["requirements"] = [writes_spec(spec_text())]
        agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
        agent.script["decompose"] = [breakdown([TICKET])]
        orchestrate("start", "42")
        orchestrate("approve", "R-0001", "spec")
        code, out = orchestrate("approve", "R-0001", "tickets")
        assert code == 0, out
        return github.pr_for_head("docs/run-R-0001")

    return run


Plan = Callable[..., None]


@pytest.fixture
def plan(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path, probe: Path
) -> Plan:
    """Takes Issue #42 to published tickets (#100 onwards, in order) with the docs PR merged.
    Each Lane writes its own file and documents nothing, unless a test scripts it otherwise."""

    def run(*tickets: dict[str, Any], settings: str = "") -> None:
        if settings:
            (repo / "orchestrator" / "settings.yaml").write_text(
                (repo / "orchestrator" / "settings.yaml").read_text() + settings
            )
        github.add_issue(42, "Expiring links", "Roadmap R18.")
        agent.script["requirements"] = [writes_spec(spec_text())]
        agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
        agent.script["decompose"] = [breakdown(list(tickets))]
        for t in tickets:
            key = t["key"]
            agent.script.setdefault(
                f"implement:{key}",
                [writes({"feature.txt": "shared\n", f"{key}.txt": f"{key}\n"})],
            )
            agent.script.setdefault(f"document:{key}", [writes({}, docs_updated=[])])
        orchestrate("start", "42")
        orchestrate("approve", "R-0001", "spec")
        code, out = orchestrate("approve", "R-0001", "tickets")
        assert code == 0, out
        merge(repo, github, github.pr_for_head("docs/run-R-0001"))

    return run


def implemented(agent: ScriptedAgent) -> list[str]:
    return [r.context["ticket"]["key"] for r in agent.requests if r.stage == "implement"]


def lane_pr(github: InMemoryGitHub, key: str) -> int | None:
    return next((n for n, pr in github.prs.items() if f"{key.lower()}" in pr.head), None)
