"""The real Agent (ADR 0007): one Claude Agent SDK session per agent step.

- The model comes from the Run's settings (`claude-opus-5-5` by default), passed on each request.
- Every tool call first passes the Run's policy check, as a `PreToolUse` hook (ADR 0010). It is the
  same `decide` function the Exit Gates use, so a denied action is refused before it runs, and the
  reason goes back to the agent so it can choose another way.
- Credentials are never handled here: the SDK's Claude Code process reads `ANTHROPIC_API_KEY` (or
  the engineer's Claude login) from the environment, and nothing is written anywhere.
- Each step answers with structured output (a JSON schema per Stage), and reports its tokens, cost
  and duration.
"""

import asyncio
import json
from pathlib import Path
from typing import Any

from claude_agent_sdk import (
    ClaudeAgentOptions,
    ClaudeSDKClient,
    EffortLevel,
    HookCallback,
    HookContext,
    HookInput,
    HookJSONOutput,
    HookMatcher,
    ResultMessage,
)

from orchestrator.agent import AgentFailed, StepRequest, StepResult
from orchestrator.policies import ToolCall

TOOLS = ["Read", "Glob", "Grep", "Write", "Edit", "MultiEdit", "Bash", "WebFetch"]
EFFORT: EffortLevel = "high"

GUIDANCE = """\
You work in the current directory: this step's own git worktree, branched from main. Don't commit,
push, merge or open pull requests: the orchestrator does that after its checks. Some actions are
refused by policy; when one is, the reason says why, so choose another way. When you're done, give
your answer as the structured output."""

_strings = {"type": "array", "items": {"type": "string"}}
_ticket = {
    "type": "object",
    "properties": {
        "key": {"type": "string"},
        "title": {"type": "string"},
        "what": {"type": "string"},
        "acceptance": _strings,
        "blocked_by": _strings,
        "kind": {"type": "string", "enum": ["Feature", "Fix", "Test", "Docs"]},
    },
    "required": ["key", "title", "what", "acceptance", "blocked_by", "kind"],
}
# The structured output each Stage reads (see the instructions each Stage sends).
SCHEMAS: dict[str, dict[str, Any]] = {
    "requirements": {
        "type": "object",
        "properties": {
            "question": {"type": "string"},
            "recommendation": {"type": "string"},
            "spec": {"type": "string", "description": "the spec's path, once written"},
        },
    },
    "design": {
        "type": "object",
        "properties": {"adrs": _strings, "no_adr_reason": {"type": "string"}},
        "required": ["adrs"],
    },
    "decompose": {
        "type": "object",
        "properties": {"tickets": {"type": "array", "items": _ticket}},
        "required": ["tickets"],
    },
    "implement": {
        "type": "object",
        "properties": {
            "summary": {"type": "string"},
            "spec_amendment": {
                "type": "object",
                "properties": {"text": {"type": "string"}, "reason": {"type": "string"}},
                "required": ["text", "reason"],
            },
        },
    },
    "document": {
        "type": "object",
        "properties": {"docs_updated": _strings},
        "required": ["docs_updated"],
    },
}


def policy_hook(request: StepRequest, written: list[str]) -> HookCallback:
    """The `PreToolUse` hook: the Run's policy check, before every tool call."""

    async def hook(
        data: HookInput, tool_use_id: str | None, context: HookContext
    ) -> HookJSONOutput:
        if data["hook_event_name"] != "PreToolUse":
            return {}
        call = ToolCall(data["tool_name"], dict(data["tool_input"]))
        decision = request.guard(call)
        if not decision.allowed:
            return {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": f"Blocked by policy ({decision.rule}): "
                    f"{decision.reason}",
                }
            }
        path = call.input.get("file_path")
        if call.tool in ("Write", "Edit", "MultiEdit") and path:
            written.append(_relative(str(path), request.workspace))
        return {}

    return hook


def _relative(path: str, workspace: Path) -> str:
    try:
        return str(Path(path).resolve().relative_to(workspace.resolve()))
    except ValueError:
        return path


def options(request: StepRequest, hook: HookCallback) -> ClaudeAgentOptions:
    return ClaudeAgentOptions(
        model=request.model,
        effort=EFFORT,
        cwd=request.workspace,
        allowed_tools=TOOLS,
        permission_mode="dontAsk",  # anything not allowed above is refused, never prompted
        setting_sources=["project"],  # the repository's CLAUDE.md, not the engineer's settings
        max_budget_usd=request.budget_usd or None,
        hooks={"PreToolUse": [HookMatcher(hooks=[hook])]},
        output_format={"type": "json_schema", "schema": SCHEMAS.get(request.stage, {})},
    )


def prompt(request: StepRequest) -> str:
    context = json.dumps(request.context, indent=2, ensure_ascii=False, default=str)
    return f"{request.instructions}\n\n{GUIDANCE}\n\nContext (JSON):\n{context}"


def step_result(result: ResultMessage, model: str, written: list[str]) -> StepResult:
    usage = result.usage or {}
    spent = StepResult(
        output=dict(result.structured_output or {}),
        files_changed=tuple(dict.fromkeys(written)),
        model=model,
        input_tokens=int(usage.get("input_tokens", 0))
        + int(usage.get("cache_creation_input_tokens", 0))
        + int(usage.get("cache_read_input_tokens", 0)),
        output_tokens=int(usage.get("output_tokens", 0)),
        cost_usd=float(result.total_cost_usd or 0.0),
        duration_ms=int(result.duration_ms),
    )
    if result.is_error or result.structured_output is None:
        reason = "; ".join(result.errors or []) or result.subtype
        raise AgentFailed(f"the agent step ended without its answer ({reason})", spent)
    return spent


class ClaudeAgent:
    """Runs one agent step in a fresh Claude Agent SDK session."""

    def run(self, request: StepRequest) -> StepResult:
        return asyncio.run(self._run(request))

    async def _run(self, request: StepRequest) -> StepResult:
        written: list[str] = []
        result: ResultMessage | None = None
        async with ClaudeSDKClient(options=options(request, policy_hook(request, written))) as c:
            await c.query(prompt(request))
            async for message in c.receive_response():
                if isinstance(message, ResultMessage):
                    result = message
        if result is None:
            raise AgentFailed("the agent step ended without a result", StepResult())
        return step_result(result, request.model, written)
