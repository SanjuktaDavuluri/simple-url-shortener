"""The Agent interface: every agent step goes through it (ADR 0007).

The Claude Agent SDK adapter arrives with #35; tests use a scripted agent.
"""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol


@dataclass(frozen=True)
class StepRequest:
    run: str
    stage: str
    instructions: str
    workspace: Path
    context: dict[str, Any] = field(default_factory=dict)
    allowed_tools: tuple[str, ...] = ()
    budget_usd: float = 0.0


@dataclass(frozen=True)
class StepResult:
    output: dict[str, Any] = field(default_factory=dict)
    files_changed: tuple[str, ...] = ()
    model: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    cost_usd: float = 0.0
    duration_ms: int = 0


class Agent(Protocol):
    def run(self, request: StepRequest) -> StepResult: ...
