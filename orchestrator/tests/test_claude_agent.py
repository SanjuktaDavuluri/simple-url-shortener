"""The real Agent (#35): the Claude Agent SDK adapter, tested without calling the API.

The live path is proven by `scripts/orchestrator-smoke.sh`; here we check what the adapter asks the
SDK for, that its `PreToolUse` hook is the Run's policy check, and how it reports a step.
"""

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest
from claude_agent_sdk import HookCallback, HookContext, HookInput, ResultMessage
from test_lane_end_to_end import lane_script, through_docs_pr, writes
from test_requirements_stage import spec_text, writes_spec

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.agent import AgentFailed, StepRequest, StepResult
from orchestrator.claude_agent import SCHEMAS, TOOLS, options, policy_hook, prompt, step_result
from orchestrator.policies import ToolCall, decide, load_policies

CONTEXT: HookContext = {"signal": None}


def pre_tool_use(tool: str, **tool_input: Any) -> HookInput:
    return {
        "hook_event_name": "PreToolUse",
        "session_id": "s",
        "transcript_path": "/dev/null",
        "cwd": ".",
        "tool_name": tool,
        "tool_input": tool_input,
        "tool_use_id": "t1",
    }


def call(hook: HookCallback, data: HookInput) -> dict[str, Any]:
    async def go() -> dict[str, Any]:
        return dict(await hook(data, "t", CONTEXT))

    return asyncio.run(go())


def request(workspace: Path, repo: Path, stage: str = "implement") -> StepRequest:
    policies = load_policies(repo)
    return StepRequest(
        run="R-0001",
        stage=stage,
        instructions="Implement ticket #100.",
        workspace=workspace,
        context={"ticket": {"key": "T1"}},
        budget_usd=5.0,
        model="claude-opus-5-5",
        guard=lambda call: decide(policies, stage, workspace, call),
    )


# What the adapter asks the SDK for


def test_each_step_runs_on_the_settings_model_in_its_own_worktree_within_its_budget(
    repo: Path, tmp_path: Path
) -> None:
    req = request(tmp_path, repo)

    opts = options(req, policy_hook(req, []))

    assert opts.model == "claude-opus-5-5"
    assert opts.cwd == tmp_path
    assert opts.max_budget_usd == 5.0
    assert opts.allowed_tools == TOOLS and opts.permission_mode == "dontAsk"
    assert opts.output_format == {"type": "json_schema", "schema": SCHEMAS["implement"]}
    assert list(opts.hooks or {}) == ["PreToolUse"]
    assert opts.env == {}  # credentials stay in the environment; nothing is passed or stored
    assert "Implement ticket #100." in prompt(req) and '"key": "T1"' in prompt(req)


def test_every_stage_that_calls_the_agent_has_an_output_schema() -> None:
    assert set(SCHEMAS) == {"requirements", "design", "decompose", "implement", "document"}


# The PreToolUse hook is the same policy check as the gates


def test_the_hook_denies_what_the_policy_check_blocks_and_says_why(
    repo: Path, tmp_path: Path
) -> None:
    req = request(tmp_path, repo)
    hook = policy_hook(req, [])

    output = call(hook, pre_tool_use("Bash", command="git push origin main"))

    expected = req.guard(ToolCall("Bash", {"command": "git push origin main"}))
    assert not expected.allowed
    specific = dict(output.get("hookSpecificOutput", {}))
    assert specific["permissionDecision"] == "deny"
    assert expected.reason in specific["permissionDecisionReason"]


def test_the_hook_allows_what_the_policy_check_allows_and_notes_written_files(
    repo: Path, tmp_path: Path
) -> None:
    written: list[str] = []
    hook = policy_hook(request(tmp_path, repo), written)

    allowed = call(
        hook, pre_tool_use("Write", file_path=str(tmp_path / "src" / "A.java"), content="x")
    )

    assert allowed == {} and written == ["src/A.java"]


def test_a_blocked_action_through_the_hook_is_recorded_like_any_other(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Any,
) -> None:
    """The hook calls the request's guard, which the Run gives every step: the block is a
    `policy_blocked` event with the same reason the gates would give."""

    def through_hook(req: StepRequest) -> StepResult:
        hook = policy_hook(req, [])
        call(hook, pre_tool_use("Bash", command="sudo rm -rf /"))
        return writes({"feature.txt": "x"})(req)

    lane_script(agent, through_hook)

    through_docs_pr(orchestrate, github, repo, published)

    log = (repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl").read_text()
    [blocked] = [json.loads(line) for line in log.splitlines() if '"policy_blocked"' in line]
    assert blocked["data"]["rule"] == "commands" and "sudo" in blocked["data"]["reason"]


# How a step is reported


def result(**fields: Any) -> ResultMessage:
    base: dict[str, Any] = {
        "subtype": "success",
        "duration_ms": 1234,
        "duration_api_ms": 1000,
        "is_error": False,
        "num_turns": 3,
        "session_id": "s",
        "total_cost_usd": 0.42,
        "usage": {"input_tokens": 100, "cache_read_input_tokens": 50, "output_tokens": 20},
        "structured_output": {"docs_updated": ["README.md"]},
    }
    return ResultMessage(**{**base, **fields})


def test_a_step_reports_its_output_tokens_cost_and_duration() -> None:
    step = step_result(result(), "claude-opus-5-5", ["README.md", "README.md"])

    assert step == StepResult(
        output={"docs_updated": ["README.md"]},
        files_changed=("README.md",),
        model="claude-opus-5-5",
        input_tokens=150,
        output_tokens=20,
        cost_usd=0.42,
        duration_ms=1234,
    )


@pytest.mark.parametrize(
    "fields",
    [
        {"is_error": True, "subtype": "error_max_budget_usd", "errors": ["budget reached"]},
        {"structured_output": None},
    ],
)
def test_a_step_without_its_answer_fails_but_still_reports_its_spend(
    fields: dict[str, Any],
) -> None:
    with pytest.raises(AgentFailed) as failed:
        step_result(result(**fields), "claude-opus-5-5", [])

    assert failed.value.spent.cost_usd == 0.42


# Through the Run


def test_a_failed_agent_step_is_recorded_and_pauses_the_run_for_resume(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    github.add_issue(42, "Expiring links", "Roadmap R18.")

    def fails(req: StepRequest) -> StepResult:
        raise AgentFailed(
            "the agent step ended without its answer (error_max_turns)", StepResult(cost_usd=0.3)
        )

    agent.script["requirements"] = [fails, writes_spec(spec_text())]

    _, out = orchestrate("start", "42")

    assert "State: paused" in out
    log = [
        json.loads(line)
        for line in (repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl")
        .read_text()
        .splitlines()
    ]
    assert [e["data"]["cost_usd"] for e in log if e["type"] == "agent_call"] == [0.3]
    assert "error_max_turns" in [e for e in log if e["type"] == "paused"][-1]["data"]["reason"]

    _, out = orchestrate("resume", "R-0001")

    assert "Approvals waiting: spec" in out


def test_every_step_carries_the_model_from_the_runs_settings(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent, repo: Path
) -> None:
    (repo / "orchestrator" / "settings.yaml").write_text("model: claude-sonnet-5-5\n")
    github.add_issue(42, "Expiring links", "Roadmap R18.")
    agent.script["requirements"] = [writes_spec(spec_text())]

    orchestrate("start", "42")

    assert {r.model for r in agent.requests} == {"claude-sonnet-5-5"}


# Found in the first real Run (#62)


def test_a_failed_step_says_why_in_the_sdks_own_words() -> None:
    with pytest.raises(AgentFailed) as failed:
        step_result(
            result(structured_output=None, result="You've hit your session limit · resets 2am"),
            "claude-opus-5-5",
            [],
        )

    assert "You've hit your session limit · resets 2am" in failed.value.reason


def test_a_step_sees_no_mcp_servers_not_even_the_engineers_own(repo: Path, tmp_path: Path) -> None:
    req = request(tmp_path, repo)

    opts = options(req, policy_hook(req, []))

    assert opts.strict_mcp_config is True and opts.mcp_servers == {}
