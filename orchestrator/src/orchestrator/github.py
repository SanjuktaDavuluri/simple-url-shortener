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


@dataclass(frozen=True)
class Check:
    name: str
    state: str  # "pending" | "success" | "failure"
    details: str = ""


@dataclass(frozen=True)
class PullRequest:
    number: int
    head: str
    base: str
    title: str
    body: str
    state: str = "open"  # "open" | "merged" | "closed"
    merged_by: str | None = None
    commits: tuple[str, ...] = ()  # commit subjects


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
    def milestone_for_release(self, release: str) -> str | None: ...
    def create_issue(
        self, title: str, body: str, labels: tuple[str, ...], milestone: str | None
    ) -> int: ...
    def add_blocked_by(self, issue: int, blocker: int) -> None: ...
    def add_to_board(self, issue: int, fields: dict[str, str]) -> None: ...
    def create_pr(self, head: str, base: str, title: str, body: str) -> int: ...
    def pr(self, number: int) -> PullRequest: ...
    def pr_checks(self, number: int, sha: str) -> list[Check]: ...
    def close_pr(self, number: int) -> None: ...


def _subject(commit: dict[str, str]) -> str:
    """A commit's full subject. GitHub cuts a long one to a headline ending in "…" and starts the
    body with "…" and the rest of it (#69)."""
    headline, body = commit["messageHeadline"], commit.get("messageBody") or ""
    if headline.endswith("…") and body.startswith("…"):
        return headline[:-1] + body[1:].split("\n", 1)[0]
    return headline


class GhCliGitHub:
    """Uses the engineer's existing `gh` login; nothing is stored."""

    def __init__(self, repo_root: Path, board_owner: str = "", board_number: int = 0) -> None:
        self.repo_root = repo_root
        self.board_owner = board_owner
        self.board_number = board_number

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

    def milestone_for_release(self, release: str) -> str | None:
        milestones = self._api(f"repos/{self.repo}/milestones?state=open")
        return next(
            (m["title"] for m in milestones if m["title"].startswith(f"Release {release}:")), None
        )

    def create_issue(
        self, title: str, body: str, labels: tuple[str, ...], milestone: str | None
    ) -> int:
        args = ["issue", "create", "--title", title, "--body", body]
        for label in labels:
            args += ["--label", label]
        if milestone:
            args += ["--milestone", milestone]
        url = self._gh(*args).strip()
        return int(url.rsplit("/", 1)[1])

    def add_blocked_by(self, issue: int, blocker: int) -> None:
        blocker_id = self._api(f"repos/{self.repo}/issues/{blocker}")["id"]
        self._gh(
            "api",
            "-X",
            "POST",
            f"repos/{self.repo}/issues/{issue}/dependencies/blocked_by",
            "-F",
            f"issue_id={blocker_id}",
        )

    def add_to_board(self, issue: int, fields: dict[str, str]) -> None:
        """Adds the Issue to the delivery board. Options match by name, or by `<value> ` prefix."""
        if not self.board_owner or not self.board_number:
            return
        board = ["--owner", self.board_owner]
        number = str(self.board_number)
        url = f"https://github.com/{self.repo}/issues/{issue}"
        item = json.loads(
            self._gh("project", "item-add", number, *board, "--url", url, "--format", "json")
        )
        project_id = json.loads(self._gh("project", "view", number, *board, "--format", "json"))[
            "id"
        ]
        field_list = json.loads(
            self._gh("project", "field-list", number, *board, "--format", "json")
        )
        for field in field_list["fields"]:
            wanted = fields.get(field["name"])
            if wanted is None or "options" not in field:
                continue
            option = next(
                (
                    o
                    for o in field["options"]
                    if o["name"] == wanted or o["name"].startswith(f"{wanted} ")
                ),
                None,
            )
            if option:
                self._gh(
                    "project",
                    "item-edit",
                    "--id",
                    item["id"],
                    "--project-id",
                    project_id,
                    "--field-id",
                    field["id"],
                    "--single-select-option-id",
                    option["id"],
                )

    def create_pr(self, head: str, base: str, title: str, body: str) -> int:
        url = self._gh(
            "pr", "create", "--head", head, "--base", base, "--title", title, "--body", body
        ).strip()
        return int(url.rsplit("/", 1)[1])

    def pr(self, number: int) -> PullRequest:
        data = json.loads(
            self._gh(
                "pr",
                "view",
                str(number),
                "--json",
                "number,headRefName,baseRefName,title,body,state,mergedBy,commits",
            )
        )
        state = {"MERGED": "merged", "CLOSED": "closed"}.get(data["state"], "open")
        return PullRequest(
            number=data["number"],
            head=data["headRefName"],
            base=data["baseRefName"],
            title=data["title"],
            body=data["body"] or "",
            state=state,
            merged_by=(data.get("mergedBy") or {}).get("login"),
            commits=tuple(_subject(c) for c in data["commits"]),
        )

    def pr_checks(self, number: int, sha: str) -> list[Check]:
        """Required checks for the PR's head, which must be `sha`; otherwise they are pending."""
        head = self._gh("pr", "view", str(number), "--json", "headRefOid", "-q", ".headRefOid")
        if head.strip() != sha:
            return [Check("head", "pending", f"waiting for {sha[:12]} to reach the PR")]
        result = subprocess.run(
            ["gh", "pr", "checks", str(number), "--required", "--json", "name,bucket,link"],
            cwd=self.repo_root,
            capture_output=True,
            text=True,
        )
        if not result.stdout.strip():
            return []
        buckets = {"pass": "success", "fail": "failure", "cancel": "failure"}
        return [
            Check(c["name"], buckets.get(c["bucket"], "pending"), c.get("link", ""))
            for c in json.loads(result.stdout)
        ]

    def close_pr(self, number: int) -> None:
        self._gh("pr", "close", str(number))
