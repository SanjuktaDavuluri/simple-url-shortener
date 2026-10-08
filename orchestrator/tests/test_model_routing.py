"""Per-step model routing (#110, ADR 0024): each agent step gets its configured model and effort,
and a routing mistake stops the Run before it starts."""

from pathlib import Path

import pytest
from test_design_decompose import writes_adrs
from test_requirements_stage import spec_text, writes_spec

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent

ROUTED = """\
model: claude-sonnet-5-5
effort: medium
stage_models:
  requirements: claude-opus-5-5
  document: claude-haiku-4-5-20251001
"""


def settings(repo: Path, text: str) -> None:
    (repo / "orchestrator").mkdir(exist_ok=True)
    (repo / "orchestrator" / "settings.yaml").write_text(text)


def test_each_step_gets_its_routed_model_or_the_default_and_the_effort(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    settings(repo, ROUTED)
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]

    orchestrate("start", "42")
    orchestrate("approve", "R-0001", "spec")

    used = {r.stage: (r.model, r.effort) for r in agent.requests}
    assert used["requirements"] == ("claude-opus-5-5", "medium")  # routed
    assert used["design"] == ("claude-sonnet-5-5", "medium")  # not routed: the default model


@pytest.mark.parametrize(
    ("text", "message"),
    [
        ("stage_models:\n  documnet: claude-haiku-4-5-20251001\n", "unknown agent steps: documnet"),
        ("effort: extreme\n", "effort must be one of"),
        ("stage_models:\n  document: haiku\n", "Claude model ID"),
    ],
)
def test_a_routing_mistake_refuses_to_start_the_run(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path, text: str, message: str
) -> None:
    settings(repo, text)
    github.add_issue(42, "Expiring links", "Roadmap R18.")

    code, out = orchestrate("start", "42")

    assert code == 2
    assert "Refusing to start" in out and message in out
    assert not (repo / ".orchestrator" / "runs" / "R-0001").exists()
