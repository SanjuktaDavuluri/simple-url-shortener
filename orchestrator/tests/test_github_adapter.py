"""The `gh` adapter, offline: `gh`'s JSON output is replayed, nothing reaches GitHub."""

import json
from pathlib import Path
from typing import Any

from orchestrator.github import GhCliGitHub

# `gh pr view 64 --json ...commits` as recorded in Run R-0001 (#69): GitHub cuts a subject longer
# than about 70 characters to a headline ending in "…" and moves the rest, starting with "…", to
# the body.
CUT = "Record one Click for every successful Redirect (tracer bullet: …"
REST = "…Redirect → Click Recorder → Click Store → clicks table) (#52)"
PR_64 = {
    "number": 64,
    "headRefName": "feat/52-record-one-click-for-every-successful-redirect-t",
    "baseRefName": "main",
    "title": "feat: Record one Click for every successful Redirect (#52)",
    "body": "Closes #52",
    "state": "MERGED",
    "mergedBy": {"login": "SanjuktaDavuluri"},
    "commits": [
        {
            "messageHeadline": f"feat: {CUT}",
            "messageBody": REST,
        },
        {
            "messageHeadline": f"docs: {CUT}",
            "messageBody": REST,
        },
    ],
}


def replaying(output: dict[str, Any]) -> GhCliGitHub:
    github = GhCliGitHub(Path("."))
    github._gh = lambda *args: json.dumps(output)  # type: ignore[method-assign]
    return github


def with_commits(*commits: dict[str, str]) -> dict[str, Any]:
    return {**PR_64, "commits": list(commits)}


def test_a_long_subject_split_across_headline_and_body_is_returned_whole() -> None:
    pr = replaying(PR_64).pr(64)

    assert pr.commits == (
        "feat: Record one Click for every successful Redirect (tracer bullet: "
        "Redirect → Click Recorder → Click Store → clicks table) (#52)",
        "docs: Record one Click for every successful Redirect (tracer bullet: "
        "Redirect → Click Recorder → Click Store → clicks table) (#52)",
    )


def test_only_the_first_line_of_the_body_completes_the_subject() -> None:
    commit = {
        "messageHeadline": "feat: a subject long enough to be cut by GitHub somewhere about h…",
        "messageBody": "…ere (#7)\n\nWhy: a longer explanation that is not part of the subject.",
    }

    pr = replaying(with_commits(commit)).pr(64)

    assert pr.commits == (
        "feat: a subject long enough to be cut by GitHub somewhere about here (#7)",
    )


def test_a_short_subject_is_returned_unchanged_whatever_its_body() -> None:
    commit = {"messageHeadline": "fix: short (#7)", "messageBody": "Some details.\n\nMore."}

    pr = replaying(with_commits(commit)).pr(64)

    assert pr.commits == ("fix: short (#7)",)


def test_a_subject_ending_in_an_ellipsis_is_unchanged_when_the_body_does_not_continue_it() -> None:
    commit = {"messageHeadline": "docs: and so on…", "messageBody": "A body of its own (#7)"}

    pr = replaying(with_commits(commit)).pr(64)

    assert pr.commits == ("docs: and so on…",)
