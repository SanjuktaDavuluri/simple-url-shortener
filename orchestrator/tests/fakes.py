"""Test doubles for the orchestrator's two external adapters (spec 0002, seam 1)."""

from dataclasses import dataclass, field

from orchestrator.agent import StepRequest, StepResult
from orchestrator.github import Issue, IssueNotFound


@dataclass
class InMemoryGitHub:
    """GitHub held in memory: Issues and the comments posted on them."""

    issues: dict[int, Issue] = field(default_factory=dict)
    comments: dict[int, list[str]] = field(default_factory=dict)

    def add_issue(
        self, number: int, title: str, body: str = "", labels: tuple[str, ...] = ()
    ) -> Issue:
        issue = Issue(number=number, title=title, body=body, labels=labels)
        self.issues[number] = issue
        return issue

    def get_issue(self, number: int) -> Issue:
        if number not in self.issues:
            raise IssueNotFound(number)
        return self.issues[number]


@dataclass
class ScriptedAgent:
    """An agent that returns pre-scripted results per Stage, in order, and records each request."""

    script: dict[str, list[StepResult]] = field(default_factory=dict)
    requests: list[StepRequest] = field(default_factory=list)

    def run(self, request: StepRequest) -> StepResult:
        self.requests.append(request)
        queue = self.script.get(request.stage, [])
        if not queue:
            raise AssertionError(f"no scripted result left for stage {request.stage!r}")
        return queue.pop(0)
