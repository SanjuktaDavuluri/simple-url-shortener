"""Per-step model routing (#110, ADR 0024): each agent step gets its configured model and effort,
and a routing mistake stops the Run before it starts."""

from pathlib import Path

import pytest
from test_design_decompose import writes_adrs
from test_failure_handling import of_type
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
        ("stage_efforts:\n  implement: extreme\n", "stage_efforts must be one of"),
        ("stage_efforts:\n  implment: low\n", "stage_efforts names unknown agent steps"),
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


def test_an_effort_routed_to_a_step_beats_the_default_effort(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    settings(repo, ROUTED + "stage_efforts:\n  design: low\n")
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]

    orchestrate("start", "42")
    orchestrate("approve", "R-0001", "spec")

    used = {r.stage: r.effort for r in agent.requests}
    assert used["requirements"] == "medium"
    assert used["design"] == "low"


def test_resume_applies_a_routing_change_to_a_run_already_started(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    settings(repo, "model: claude-opus-5-5\neffort: high\n")
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
    orchestrate("start", "42")

    settings(repo, "model: claude-sonnet-5-5\neffort: high\nstage_efforts:\n  design: low\n")
    orchestrate("resume", "R-0001")
    orchestrate("approve", "R-0001", "spec")

    assert of_type(repo, "settings_changed")[0]["data"] == {
        "model": "claude-sonnet-5-5",
        "stage_efforts": {"design": "low"},
    }
    design = next(r for r in agent.requests if r.stage == "design")
    assert (design.model, design.effort) == ("claude-sonnet-5-5", "low")

    orchestrate("resume", "R-0001")
    assert len(of_type(repo, "settings_changed")) == 1  # nothing changed, nothing logged
