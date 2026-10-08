"""The GitHub adapter interface, and the real adapter backed by the `gh` CLI."""

import json
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class Issue:
    number: int
    title: str
    body: str = ""
    labels: tuple[str, ...] = ()


class IssueNotFound(Exception):
    def __init__(self, number: int) -> None:
        super().__init__(f"Issue #{number} was not found")
        self.number = number


class GitHub(Protocol):
    def get_issue(self, number: int) -> Issue: ...


class GhCliGitHub:
    """Uses the engineer's existing `gh` login; nothing is stored."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root

    def get_issue(self, number: int) -> Issue:
        result = subprocess.run(
            ["gh", "issue", "view", str(number), "--json", "number,title,body,labels"],
            cwd=self.repo_root,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise IssueNotFound(number)
        data = json.loads(result.stdout)
        return Issue(
            number=data["number"],
            title=data["title"],
            body=data["body"] or "",
            labels=tuple(label["name"] for label in data["labels"]),
        )
