"""Test doubles for the orchestrator's two external adapters (spec 0002, seam 1)."""

from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from itertools import count

from orchestrator.agent import StepRequest, StepResult
from orchestrator.github import Comment, Issue, IssueNotFound, LabelEvent

_clock = count(1)


def _now() -> str:
    """A strictly increasing timestamp, so ordering in tests never depends on the wall clock."""
    tick = next(_clock)
    return f"2026-10-08T{tick // 3600:02d}:{tick // 60 % 60:02d}:{tick % 60:02d}Z"


@dataclass
class InMemoryGitHub:
    """GitHub in memory: Issues, their comments and label events, and the repo's maintainers."""

    user: str = "maintainer"
    maintainers: set[str] = field(default_factory=lambda: {"maintainer"})
    issues: dict[int, Issue] = field(default_factory=dict)
    _comments: dict[int, list[Comment]] = field(default_factory=dict)
    _label_events: dict[int, list[LabelEvent]] = field(default_factory=dict)
    _ids: Iterator[int] = field(default_factory=lambda: count(1000))

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

    def current_user(self) -> str:
        return self.user

    def comments(self, number: int) -> list[Comment]:
        return list(self._comments.get(number, []))

    def add_comment(self, number: int, body: str) -> Comment:
        return self.reply(number, body, author=self.user)

    def reply(self, number: int, body: str, author: str = "maintainer") -> Comment:
        comment = Comment(id=next(self._ids), author=author, body=body, created_at=_now())
        self._comments.setdefault(number, []).append(comment)
        return comment

    def add_label(self, number: int, label: str, by: str = "maintainer") -> None:
        self._label_events.setdefault(number, []).append(LabelEvent(label, by, _now()))

    def label_events(self, number: int) -> list[LabelEvent]:
        return list(self._label_events.get(number, []))

    def is_maintainer(self, user: str) -> bool:
        return user in self.maintainers

    def file_url(self, ref: str, path: str) -> str:
        return f"https://github.test/blob/{ref}/{path}"


Scripted = StepResult | Callable[[StepRequest], StepResult]


@dataclass
class ScriptedAgent:
    """Returns pre-scripted results per Stage, in order, and records every request.

    A scripted entry may be a function of the request, so it can write files into the workspace
    the way a real agent would. With nothing scripted for a Stage, it asks a generic question.
    """

    script: dict[str, list[Scripted]] = field(default_factory=dict)
    requests: list[StepRequest] = field(default_factory=list)

    def run(self, request: StepRequest) -> StepResult:
        self.requests.append(request)
        queue = self.script.get(request.stage, [])
        if not queue:
            return StepResult(
                output={"question": "What should happen?", "recommendation": "Keep it simple."}
            )
        entry = queue.pop(0)
        return entry(request) if callable(entry) else entry
