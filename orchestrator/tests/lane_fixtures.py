"""Fixtures shared by the Lane tests: settings probes, and a Run taken to one published ticket."""

from collections.abc import Callable
from pathlib import Path

import pytest
from test_design_decompose import breakdown, ticket, writes_adrs
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
