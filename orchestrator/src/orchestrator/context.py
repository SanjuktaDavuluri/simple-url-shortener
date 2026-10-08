"""What every Stage node needs: where the Run lives, its adapters, and how it reports progress."""

from dataclasses import dataclass
from typing import Any

from orchestrator.agent import Agent, StepRequest, StepResult
from orchestrator.events import EventLog
from orchestrator.github import Comment, GitHub
from orchestrator.settings import Settings
from orchestrator.workspace import Workspace


def marker(run: str) -> str:
    """Hidden in each comment the orchestrator posts, so its own comments never count as answers."""
    return f"<!-- orchestrator:{run} -->"


@dataclass(frozen=True)
class RunContext:
    run: str
    issue: int
    workspace: Workspace
    github: GitHub
    agent: Agent | None
    settings: Settings

    @property
    def log(self) -> EventLog:
        return self.workspace.log(self.run)

    def event(
        self,
        type: str,
        stage: str | None = None,
        data: dict[str, Any] | None = None,
        actor: str = "orchestrator",
    ) -> dict[str, Any]:
        return self.log.append(run=self.run, actor=actor, stage=stage, type=type, data=data)

    def mirror(self, text: str) -> Comment:
        """Post progress on the Run's Issue: the readable mirror of the Event Log (ADR 0009)."""
        return self.github.add_comment(self.issue, f"{marker(self.run)}\n**{self.run}** · {text}")

    def call_agent(self, request: StepRequest) -> StepResult:
        if self.agent is None:
            raise RuntimeError(
                "No agent is configured; the Claude Agent SDK adapter arrives with #35"
            )
        result = self.agent.run(request)
        self.event(
            "agent_call",
            stage=request.stage,
            actor="agent",
            data={
                "model": result.model or self.settings.model,
                "input_tokens": result.input_tokens,
                "output_tokens": result.output_tokens,
                "cost_usd": result.cost_usd,
                "duration_ms": result.duration_ms,
            },
        )
        return result
