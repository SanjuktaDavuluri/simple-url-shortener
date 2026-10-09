"""Test doubles for the orchestrator's two external adapters (spec 0002, seam 1)."""

import threading
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from itertools import count

from orchestrator.agent import StepRequest, StepResult
from orchestrator.github import Check, Comment, Issue, IssueNotFound, LabelEvent, PullRequest

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
    milestones: list[str] = field(
        default_factory=lambda: [
            "Release 1: Greenfield v1 (v1.0.0)",
            "Release 2: Orchestrated delivery",
        ]
    )
    created: list[int] = field(default_factory=list)
    issue_meta: dict[int, dict[str, object]] = field(default_factory=dict)
    blocked_by: dict[int, list[int]] = field(default_factory=dict)
    board: dict[int, dict[str, str]] = field(default_factory=dict)
    prs: dict[int, PullRequest] = field(default_factory=dict)
    checks: dict[tuple[int, str], list[Check]] = field(default_factory=dict)
    default_checks: tuple[Check, ...] = (Check("Verify", "success", ""),)
    _ids: Iterator[int] = field(default_factory=lambda: count(1000))
    _numbers: Iterator[int] = field(default_factory=lambda: count(100))

    def add_issue(
        self, number: int, title: str, body: str = "", labels: tuple[str, ...] = ()
    ) -> Issue:
        issue = Issue(number=number, title=title, body=body, labels=labels)
        self.issues[number] = issue
        return issue

    def set_labels(self, number: int, *labels: str) -> None:
        issue = self.issues[number]
        self.issues[number] = Issue(issue.number, issue.title, issue.body, tuple(labels))

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

    def milestone_for_release(self, release: str) -> str | None:
        return next((m for m in self.milestones if m.startswith(f"Release {release}:")), None)

    def create_issue(
        self, title: str, body: str, labels: tuple[str, ...], milestone: str | None
    ) -> int:
        number = next(self._numbers)
        self.add_issue(number, title, body, labels)
        self.issue_meta[number] = {"milestone": milestone}
        self.created.append(number)
        return number

    def add_blocked_by(self, issue: int, blocker: int) -> None:
        """Like the real one: a blocker already recorded is not added again."""
        if blocker not in self.blocked_by.setdefault(issue, []):
            self.blocked_by[issue].append(blocker)

    def add_to_board(self, issue: int, fields: dict[str, str]) -> None:
        """Like the real board: adds the item once, and sets only the fields named."""
        self.board[issue] = {**self.board.get(issue, {}), **fields}

    def create_pr(self, head: str, base: str, title: str, body: str) -> int:
        number = next(self._numbers)
        self.prs[number] = PullRequest(number, head, base, title, body)
        return number

    def pr(self, number: int) -> PullRequest:
        return self.prs[number]

    def pr_checks(self, number: int, sha: str) -> list[Check]:
        return list(self.checks.get((number, sha), self.default_checks))

    def set_checks(self, number: int, sha: str, *checks: Check) -> None:
        """Checks are per commit, as on GitHub: a new push starts with the default checks."""
        self.checks[(number, sha)] = list(checks)

    def record_merge(self, number: int, by: str, commits: list[str]) -> None:
        pr = self.prs[number]
        self.prs[number] = PullRequest(
            pr.number, pr.head, pr.base, pr.title, pr.body, "merged", by, tuple(commits)
        )

    def close_pr(self, number: int) -> None:
        pr = self.prs[number]
        self.prs[number] = PullRequest(pr.number, pr.head, pr.base, pr.title, pr.body, "closed")

    def pr_for_head(self, head: str) -> int:
        return next(n for n, pr in self.prs.items() if pr.head == head)


Scripted = StepResult | Callable[[StepRequest], StepResult]


@dataclass
class ScriptedAgent:
    """Returns pre-scripted results per Stage, in order, and records every request.

    A scripted entry may be a function of the request, so it can write files into the workspace
    the way a real agent would. Lanes run concurrently, so a Lane's steps can be scripted under
    `"<stage>:<lane key>"`; otherwise they come from the Stage's own list. With nothing scripted,
    it asks a generic question.
    """

    script: dict[str, list[Scripted]] = field(default_factory=dict)
    requests: list[StepRequest] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def run(self, request: StepRequest) -> StepResult:
        with self._lock:
            self.requests.append(request)
            lane = request.context.get("ticket", {}).get("key")
            queue = self.script.get(f"{request.stage}:{lane}") or self.script.get(request.stage)
            entry = queue.pop(0) if queue else None
        if entry is None:
            return StepResult(
                output={"question": "What should happen?", "recommendation": "Keep it simple."}
            )
        return entry(request) if callable(entry) else entry
