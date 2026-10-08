"""The Agent interface: every agent step goes through it (ADR 0007).

The real adapter is `claude_agent.ClaudeAgent` (the Claude Agent SDK); tests use a scripted agent.
"""

from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from orchestrator.policies import Decision, ToolCall


@dataclass(frozen=True)
class StepRequest:
    run: str
    stage: str
    instructions: str
    workspace: Path
    context: dict[str, Any] = field(default_factory=dict)
    allowed_tools: tuple[str, ...] = ()
    budget_usd: float = 0.0
    model: str = ""  # from the Run's settings, set for every step
    effort: str = "high"
    # The policy check every action must pass before it runs (the PreToolUse hook, ADR 0010).
    guard: Callable[[ToolCall], Decision] = field(default=lambda call: Decision(True))


@dataclass(frozen=True)
class StepResult:
    output: dict[str, Any] = field(default_factory=dict)
    files_changed: tuple[str, ...] = ()
    model: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    cost_usd: float = 0.0
    duration_ms: int = 0


class AgentFailed(Exception):
    """The agent step ended in an error. Its spend is still reported, so it can be recorded."""

    def __init__(self, reason: str, spent: StepResult) -> None:
        super().__init__(reason)
        self.reason, self.spent = reason, spent


class Agent(Protocol):
    def run(self, request: StepRequest) -> StepResult: ...
