"""`orchestrate`: the command-line entry point of the delivery orchestrator (spec 0002)."""

import argparse
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from orchestrator.agent import Agent
from orchestrator.events import verify
from orchestrator.github import GhCliGitHub, GitHub, IssueNotFound
from orchestrator.settings import load_settings
from orchestrator.stages import STAGES, run_graph
from orchestrator.workspace import Workspace

USAGE_ERROR = 2


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
    check = commands.add_parser("verify", help="check that Event Logs are intact")
    target = check.add_mutually_exclusive_group(required=True)
    target.add_argument("run", nargs="?", help="a Run ID such as R-0001")
    target.add_argument("--all", action="store_true", help="check every local and committed Run")
    return parser


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
    run_graph(workspace, issue, {"run": run, "issue": issue.number})
    print(f"{run} started for Issue #{issue.number}: {issue.title.strip()}")
    _print_stages(workspace, run)
    return 0


def _stage_states(workspace: Workspace, run: str) -> dict[str, str]:
    states = dict.fromkeys(STAGES, "pending")
    transitions = {"stage_started": "running", "stage_passed": "passed", "stage_failed": "failed"}
    for event in workspace.log(run).read():
        if event["stage"] in states and event["type"] in transitions:
            states[event["stage"]] = transitions[event["type"]]
    return states


def _print_stages(workspace: Workspace, run: str) -> None:
    for stage, state in _stage_states(workspace, run).items():
        print(f"  {stage:<18} {state}")


def _summary(workspace: Workspace, run: str) -> tuple[str, str, float]:
    log = workspace.log(run).read()
    started = next(e for e in log if e["type"] == "run_started")
    finished = any(e["type"] == "run_finished" for e in log)
    title = next(
        (
            e["data"]["artifacts"]["issue"]["title"]
            for e in log
            if e["type"] == "stage_passed" and e["stage"] == "intake"
        ),
        "",
    )
    cost = sum(e["data"].get("cost_usd", 0.0) for e in log if e["type"] == "agent_call")
    state = "finished" if finished else "active"
    return f"#{started['data']['issue']} {title}".rstrip(), state, cost


def _status(workspace: Workspace, run: str | None) -> int:
    if run is None:
        runs = workspace.run_ids()
        if not runs:
            print("No Runs yet. Start one with: orchestrate start <issue>")
        for r in runs:
            issue, state, _ = _summary(workspace, r)
            print(f"{r}  {state:<8} {issue}")
        return 0
    if not workspace.exists(run):
        print(f"Run {run} was not found", file=sys.stderr)
        return USAGE_ERROR
    issue, state, cost = _summary(workspace, run)
    print(f"{run} · {issue}")
    print(f"State: {state}")
    print("Stages:")
    _print_stages(workspace, run)
    print("Approvals waiting: none")
    print(f"Cost so far: ${cost:.2f}")
    return 0


def _verify(workspace: Workspace, run: str | None) -> int:
    runs = workspace.run_ids() if run is None else [run]
    if run is not None and not workspace.exists(run):
        print(f"Run {run} was not found", file=sys.stderr)
        return USAGE_ERROR
    broken = False
    for r in runs:
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
    return Deps(repo_root=root, github=GhCliGitHub(root))


def main(argv: Sequence[str] | None = None, *, deps: Deps | None = None) -> int:
    args = _parser().parse_args(argv)
    deps = deps or _real_deps()
    workspace = Workspace(deps.repo_root)
    if args.command == "start":
        return _start(deps, workspace, args.issue)
    if args.command == "status":
        return _status(workspace, args.run)
    return _verify(workspace, None if args.all else args.run)


if __name__ == "__main__":
    sys.exit(main())
