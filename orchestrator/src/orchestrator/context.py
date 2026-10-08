"""What every Stage node needs: where the Run lives, its adapters, and how it reports progress."""

from dataclasses import dataclass, replace
from typing import Any

from orchestrator.agent import Agent, StepRequest, StepResult
from orchestrator.events import EventLog
from orchestrator.github import Comment, GitHub
from orchestrator.policies import Decision, Policies, ToolCall, decide
from orchestrator.settings import Settings
from orchestrator.workspace import Workspace


class RunPaused(Exception):
    """Raised inside a Stage to Safe-stop the Run; `resume` re-runs that Stage's current step."""

    def __init__(self, stage: str, reason: str) -> None:
        super().__init__(reason)
        self.stage, self.reason = stage, reason


class RunStopped(RunPaused):
    """A Safe-stop: requested by the engineer, a `stop` label, or a cost cap."""


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
    policies: Policies

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

    def spent(self) -> float:
        return float(
            sum(e["data"]["cost_usd"] for e in self.log.read() if e["type"] == "agent_call")
        )

    def request_stop(self, reason: str) -> None:
        flag = self.workspace.stop_flag(self.run)
        flag.parent.mkdir(parents=True, exist_ok=True)
        flag.write_text(reason)

    def check_boundary(self, stage: str | None) -> None:
        """Between steps: honour a requested Safe-stop or a `stop` label on the Issue."""
        flag = self.workspace.stop_flag(self.run)
        if flag.exists():
            raise RunStopped(stage or "", flag.read_text() or "orchestrate stop")
        if "stop" in self.github.get_issue(self.issue).labels:
            raise RunStopped(stage or "", f"the stop label is on Issue #{self.issue}")

    def call_agent(self, request: StepRequest) -> StepResult:
        spent, cap = self.spent(), self.settings.cost_cap_run_usd
        if spent >= cap:
            self.event(
                "cost_cap_reached",
                request.stage,
                {"scope": "run", "cost_usd": round(spent, 4), "cap_usd": cap},
            )
            raise RunStopped(
                request.stage, f"the Run reached its cost cap (${spent:.2f} of ${cap:.2f})"
            )
        if self.agent is None:
            raise RuntimeError(
                "No agent is configured; the Claude Agent SDK adapter arrives with #35"
            )
        blocked: list[Decision] = []

        def guard(call: ToolCall) -> Decision:
            decision = decide(self.policies, request.stage, request.workspace, call)
            if not decision.allowed:
                blocked.append(decision)
                self.event(
                    "policy_blocked",
                    request.stage,
                    {"tool": call.tool, "rule": decision.rule, "reason": decision.reason},
                )
            return decision

        result = self.agent.run(replace(request, guard=guard))
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
        step_cap = self.settings.cost_cap_step_usd
        if result.cost_usd > step_cap:
            self.event(
                "cost_cap_reached",
                request.stage,
                {"scope": "step", "cost_usd": result.cost_usd, "cap_usd": step_cap},
            )
            self.request_stop(
                f"a step exceeded its cost cap (${result.cost_usd:.2f} of ${step_cap:.2f})"
            )
        if len(blocked) > self.settings.max_retries:
            raise RunPaused(
                request.stage, f"{len(blocked)} actions were blocked by policy in one step"
            )
        return result
