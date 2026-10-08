"""`orchestrate`: the command-line entry point of the delivery orchestrator (spec 0002)."""

import argparse
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from orchestrator import graph
from orchestrator.agent import Agent
from orchestrator.context import RunContext, marker
from orchestrator.events import verify
from orchestrator.github import GhCliGitHub, GitHub, IssueNotFound
from orchestrator.hashing import content_hash
from orchestrator.settings import Settings, load_settings
from orchestrator.stages import STAGES
from orchestrator.workspace import Workspace

USAGE_ERROR = 2
REFUSED = 1


@dataclass(frozen=True)
class Deps:
    repo_root: Path
    github: GitHub
    agent: Agent | None = None


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="orchestrate",
        description="Drive a GitHub Issue through gated delivery Stages (spec 0002).",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("start", help="start a Run for a GitHub Issue")
    start.add_argument("issue", type=int, help="the Issue number")
    status = commands.add_parser("status", help="show a Run's Stages, or list every Run")
    status.add_argument("run", nargs="?", help="a Run ID such as R-0001")
    resume = commands.add_parser(
        "resume", help="continue a Run: pick up answers, approvals and labels from GitHub"
    )
    resume.add_argument("run")
    approve = commands.add_parser("approve", help="approve an Approval Checkpoint")
    approve.add_argument("run")
    approve.add_argument("checkpoint", help="for example: spec")
    reject = commands.add_parser("reject", help="reject an Approval Checkpoint, with a reason")
    reject.add_argument("run")
    reject.add_argument("checkpoint")
    reject.add_argument("--reason", required=True)
    check = commands.add_parser("verify", help="check that Event Logs are intact")
    target = check.add_mutually_exclusive_group(required=True)
    target.add_argument("run", nargs="?", help="a Run ID such as R-0001")
    target.add_argument("--all", action="store_true", help="check every local and committed Run")
    return parser


def _context(deps: Deps, workspace: Workspace, run: str) -> RunContext:
    started = next(e for e in workspace.log(run).read() if e["type"] == "run_started")
    return RunContext(
        run=run,
        issue=started["data"]["issue"],
        workspace=workspace,
        github=deps.github,
        agent=deps.agent,
        settings=Settings(**started["data"]["settings"]),
    )


# Reading a Run's progress from its Event Log, the system of record


def _stage_states(log: list[dict[str, Any]]) -> dict[str, str]:
    states = dict.fromkeys(STAGES, "pending")
    transitions = {"stage_started": "running", "stage_passed": "passed", "stage_failed": "failed"}
    for event in log:
        if event["stage"] in states and event["type"] in transitions:
            states[event["stage"]] = transitions[event["type"]]
    return states


def _open_question(log: list[dict[str, Any]]) -> int | None:
    asked = [e["data"]["n"] for e in log if e["type"] == "question_asked"]
    answered = {e["data"]["n"] for e in log if e["type"] == "answer_received"}
    open_ = [n for n in asked if n not in answered]
    return open_[-1] if open_ else None


def _open_approvals(log: list[dict[str, Any]]) -> list[str]:
    waiting: dict[str, bool] = {}
    for e in log:
        if e["type"] == "approval_requested":
            waiting[e["data"]["checkpoint"]] = True
        elif e["type"] in ("approved", "rejected"):
            waiting[e["data"]["checkpoint"]] = False
    return [checkpoint for checkpoint, open_ in waiting.items() if open_]


def _run_state(log: list[dict[str, Any]]) -> str:
    if any(e["type"] == "run_finished" for e in log):
        return "finished"
    pauses = [e["type"] for e in log if e["type"] in ("paused", "resumed")]
    return "paused" if pauses and pauses[-1] == "paused" else "active"


def _title(log: list[dict[str, Any]]) -> str:
    return next(
        (
            e["data"]["artifacts"]["issue"]["title"]
            for e in log
            if e["type"] == "stage_passed" and e["stage"] == "intake"
        ),
        "",
    )


def _print_run(workspace: Workspace, run: str) -> None:
    log = workspace.log(run).read()
    issue = log[0]["data"]["issue"]
    print(f"{run} · #{issue} {_title(log)}".rstrip())
    print(f"State: {_run_state(log)}")
    print("Stages:")
    for stage, state in _stage_states(log).items():
        print(f"  {stage:<18} {state}")
    question = _open_question(log)
    if question is not None:
        print(f"Waiting for: an answer to Q{question} on Issue #{issue}")
    approvals = _open_approvals(log)
    print(f"Approvals waiting: {', '.join(approvals) if approvals else 'none'}")
    cost = sum(e["data"].get("cost_usd", 0.0) for e in log if e["type"] == "agent_call")
    print(f"Cost so far: ${cost:.2f}")


# Commands


def _start(deps: Deps, workspace: Workspace, issue_number: int) -> int:
    active = workspace.active_run_for_issue(issue_number)
    if active:
        print(f"Issue #{issue_number} already has an active Run: {active}", file=sys.stderr)
        return USAGE_ERROR
    try:
        issue = deps.github.get_issue(issue_number)
    except IssueNotFound:
        print(f"Issue #{issue_number} was not found", file=sys.stderr)
        return USAGE_ERROR
    settings = load_settings(deps.repo_root)
    run = workspace.next_run_id()
    workspace.log(run).append(
        run=run,
        actor="engineer",
        type="run_started",
        data={"issue": issue.number, "settings": settings.as_dict()},
    )
    graph.start(_context(deps, workspace, run))
    _print_run(workspace, run)
    return 0


def _status(workspace: Workspace, run: str | None) -> int:
    if run is None:
        runs = workspace.run_ids()
        if not runs:
            print("No Runs yet. Start one with: orchestrate start <issue>")
        for r in runs:
            log = workspace.log(r).read()
            print(f"{r}  {_run_state(log):<8} #{log[0]['data']['issue']} {_title(log)}".rstrip())
        return 0
    if not workspace.exists(run):
        print(f"Run {run} was not found", file=sys.stderr)
        return USAGE_ERROR
    _print_run(workspace, run)
    return 0


def _submitted_artifact_changed(ctx: RunContext, waiting: dict[str, Any]) -> bool:
    file = ctx.workspace.worktrees / ctx.run / waiting["path"]
    return not file.is_file() or content_hash(file.read_text()) != waiting["hash"]


def _resume(deps: Deps, workspace: Workspace, run: str) -> int:
    ctx = _context(deps, workspace, run)
    waiting = graph.waiting_on(ctx)
    if waiting and waiting["kind"] == "answer":
        comments = ctx.github.comments(ctx.issue)
        after = [c.id for c in comments].index(waiting["comment"]) + 1
        reply = next((c for c in comments[after:] if marker(run) not in c.body), None)
        if reply:
            graph.resume(ctx, {"body": reply.body, "by": reply.author})
    elif waiting and waiting["kind"] == "approval":
        label = f"approved:{waiting['checkpoint']}"
        approval = next(
            (
                e
                for e in ctx.github.label_events(ctx.issue)
                if e.label == label
                and e.created_at > waiting["requested_at"]
                and ctx.github.is_maintainer(e.actor)
            ),
            None,
        )
        if approval:
            if _submitted_artifact_changed(ctx, waiting):
                print(_changed_message(waiting), file=sys.stderr)
                return REFUSED
            graph.resume(ctx, {"decision": "approved", "by": approval.actor, "channel": "label"})
    _print_run(workspace, run)
    return 0


def _changed_message(waiting: dict[str, Any]) -> str:
    return (
        f"{waiting['path']} changed after it was submitted for approval "
        f"(submitted hash {waiting['hash'][:12]}); it can't be approved as it is now"
    )


def _decide(deps: Deps, workspace: Workspace, args: argparse.Namespace) -> int:
    ctx = _context(deps, workspace, args.run)
    waiting = graph.waiting_on(ctx)
    if not waiting or waiting["kind"] != "approval" or waiting["checkpoint"] != args.checkpoint:
        print(f"{args.run} is not waiting for approval of {args.checkpoint}", file=sys.stderr)
        return REFUSED
    by = ctx.github.current_user()
    if args.command == "approve":
        if _submitted_artifact_changed(ctx, waiting):
            print(_changed_message(waiting), file=sys.stderr)
            return REFUSED
        graph.resume(ctx, {"decision": "approved", "by": by, "channel": "cli"})
    else:
        graph.resume(
            ctx, {"decision": "rejected", "by": by, "channel": "cli", "reason": args.reason}
        )
    _print_run(workspace, args.run)
    return 0


def _verify(workspace: Workspace, run: str | None) -> int:
    if run is not None and not workspace.exists(run):
        print(f"Run {run} was not found", file=sys.stderr)
        return USAGE_ERROR
    broken = False
    for r in workspace.run_ids() if run is None else [run]:
        result = verify(workspace.log_path(r))
        if result.intact:
            print(f"{r}: intact ({result.events} events)")
        else:
            broken = True
            print(f"{r}: broken: {result.problem}")
    return 1 if broken else 0


def _real_deps() -> Deps:
    top = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
    )
    root = Path(top.stdout.strip())
    settings = load_settings(root)
    return Deps(
        repo_root=root,
        github=GhCliGitHub(root, settings.board_owner, settings.board_number),
    )


def main(argv: Sequence[str] | None = None, *, deps: Deps | None = None) -> int:
    args = _parser().parse_args(argv)
    deps = deps or _real_deps()
    workspace = Workspace(deps.repo_root)
    if args.command == "start":
        return _start(deps, workspace, args.issue)
    if args.command == "status":
        return _status(workspace, args.run)
    if args.command == "verify":
        return _verify(workspace, None if args.all else args.run)
    if not workspace.exists(args.run):
        print(f"Run {args.run} was not found", file=sys.stderr)
        return USAGE_ERROR
    if args.command == "resume":
        return _resume(deps, workspace, args.run)
    return _decide(deps, workspace, args)


if __name__ == "__main__":
    sys.exit(main())
