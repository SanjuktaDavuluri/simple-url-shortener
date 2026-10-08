import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path

import pytest

from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.cli import Deps, main

pytest_plugins = ["lane_fixtures"]

ROADMAP = """# Roadmap

| # | Item | Release | Deferred on | Why deferred | Status | Links |
|---|---|---|---|---|---|---|
| R10 | Clickstream | 2 | 2026-10-07 | why | deferred | |
| R18 | Delivery orchestrator | 2 | 2026-10-07 | why | ticketed | |
"""


def _git(cwd: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    """A throwaway git repository with a local bare remote and a roadmap."""
    remote = tmp_path / "remote.git"
    work = tmp_path / "work"
    _git(tmp_path, "init", "--bare", "-b", "main", str(remote))
    _git(tmp_path, "init", "-b", "main", str(work))
    _git(work, "config", "user.email", "test@example.com")
    _git(work, "config", "user.name", "Test")
    (work / "docs").mkdir()
    (work / "docs" / "roadmap.md").write_text(ROADMAP)
    _git(work, "add", ".")
    _git(work, "commit", "-m", "initial")
    (work / "orchestrator").mkdir()
    shutil.copyfile(
        Path(__file__).resolve().parents[1] / "policies.yaml",
        work / "orchestrator" / "policies.yaml",
    )
    _git(work, "remote", "add", "origin", str(remote))
    _git(work, "push", "-u", "origin", "main")
    return work


@pytest.fixture
def github() -> InMemoryGitHub:
    return InMemoryGitHub()


@pytest.fixture
def agent() -> ScriptedAgent:
    return ScriptedAgent()


Orchestrate = Callable[..., tuple[int, str]]


@pytest.fixture
def orchestrate(
    repo: Path, github: InMemoryGitHub, agent: ScriptedAgent, capsys: pytest.CaptureFixture[str]
) -> Orchestrate:
    """Run the `orchestrate` command; returns (exit code, combined output)."""

    def run(*args: str) -> tuple[int, str]:
        code = main(list(args), deps=Deps(repo_root=repo, github=github, agent=agent))
        out = capsys.readouterr()
        return code, out.out + out.err

    return run
