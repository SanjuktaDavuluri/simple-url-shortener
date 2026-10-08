"""The GitHub adapter interface, and the real adapter backed by the `gh` CLI."""

import json
import subprocess
from dataclasses import dataclass
from functools import cached_property
from pathlib import Path
from typing import Any, Protocol


@dataclass(frozen=True)
class Issue:
    number: int
    title: str
    body: str = ""
    labels: tuple[str, ...] = ()


@dataclass(frozen=True)
class Comment:
    id: int
    author: str
    body: str
    created_at: str


@dataclass(frozen=True)
class LabelEvent:
    label: str
    actor: str
    created_at: str


class IssueNotFound(Exception):
    def __init__(self, number: int) -> None:
        super().__init__(f"Issue #{number} was not found")
        self.number = number


class GitHub(Protocol):
    def get_issue(self, number: int) -> Issue: ...
    def current_user(self) -> str: ...
    def comments(self, number: int) -> list[Comment]: ...
    def add_comment(self, number: int, body: str) -> Comment: ...
    def label_events(self, number: int) -> list[LabelEvent]: ...
    def is_maintainer(self, user: str) -> bool: ...
    def file_url(self, ref: str, path: str) -> str: ...


class GhCliGitHub:
    """Uses the engineer's existing `gh` login; nothing is stored."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root

    def _gh(self, *args: str) -> str:
        result = subprocess.run(
            ["gh", *args], cwd=self.repo_root, capture_output=True, text=True, check=True
        )
        return result.stdout

    def _api(self, path: str, *args: str) -> Any:
        return json.loads(self._gh("api", path, *args))

    @cached_property
    def repo(self) -> str:
        return self._gh("repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner").strip()

    def get_issue(self, number: int) -> Issue:
        try:
            data = json.loads(
                self._gh("issue", "view", str(number), "--json", "number,title,body,labels")
            )
        except subprocess.CalledProcessError as e:
            raise IssueNotFound(number) from e
        return Issue(
            number=data["number"],
            title=data["title"],
            body=data["body"] or "",
            labels=tuple(label["name"] for label in data["labels"]),
        )

    def current_user(self) -> str:
        return str(self._api("user")["login"])

    def comments(self, number: int) -> list[Comment]:
        pages = self._api(f"repos/{self.repo}/issues/{number}/comments", "--paginate")
        return [
            Comment(c["id"], c["user"]["login"], c["body"] or "", c["created_at"]) for c in pages
        ]

    def add_comment(self, number: int, body: str) -> Comment:
        c = self._api(
            f"repos/{self.repo}/issues/{number}/comments", "-X", "POST", "-f", f"body={body}"
        )
        return Comment(c["id"], c["user"]["login"], c["body"] or "", c["created_at"])

    def label_events(self, number: int) -> list[LabelEvent]:
        pages = self._api(f"repos/{self.repo}/issues/{number}/events", "--paginate")
        return [
            LabelEvent(e["label"]["name"], e["actor"]["login"], e["created_at"])
            for e in pages
            if e["event"] == "labeled"
        ]

    def is_maintainer(self, user: str) -> bool:
        try:
            data = self._api(f"repos/{self.repo}/collaborators/{user}/permission")
        except subprocess.CalledProcessError:
            return False
        return data.get("permission") in {"admin", "maintain"}

    def file_url(self, ref: str, path: str) -> str:
        return f"https://github.com/{self.repo}/blob/{ref}/{path}"
