"""`orchestrate`: the command-line entry point of the delivery orchestrator (spec 0002)."""

import argparse
import os
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from orchestrator import graph, metrics
from orchestrator.agent import Agent
from orchestrator.claude_agent import ClaudeAgent
from orchestrator.context import RunContext, marker
from orchestrator.events import verify
from orchestrator.github import GhCliGitHub, GitHub, IssueNotFound
from orchestrator.hashing import content_hash
from orchestrator.policies import PolicyError, load_policies
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
    resume.add_argument(
        "--cost-cap-run", type=float, help="raise the Run's cost cap (USD) before continuing"
    )
    stop = commands.add_parser(
        "stop", help="Safe-stop a Run: the current step finishes, later steps wait for resume"
    )
    stop.add_argument("run")
    approve = commands.add_parser("approve", help="approve an Approval Checkpoint")
    approve.add_argument("run")
    approve.add_argument(
        "checkpoint", help="spec, adr-NNNN, tickets, dependency:<lane> or amendment-N"
    )
    reject = commands.add_parser("reject", help="reject an Approval Checkpoint, with a reason")
    reject.add_argument("run")
    reject.add_argument(
        "checkpoint", help="as for approve, or lane:<key> to roll a paused Lane back"
    )
    reject.add_argument("--reason", required=True)
    replan = commands.add_parser(
        "replan", help="check a Run's approved inputs for changes, and re-plan what depends on them"
    )
    replan.add_argument("run")
    commands.add_parser(
        "metrics", help="regenerate delivery/metrics.md from every committed Event Log"
    )
    check = commands.add_parser("verify", help="check that Event Logs are intact")
    target = check.add_mutually_exclusive_group(required=True)
    target.add_argument("run", nargs="?", help="a Run ID such as R-0001")
    target.add_argument("--all", action="store_true", help="check every local and committed Run")
    return parser


def _context(deps: Deps, workspace: Workspace, run: str) -> RunContext:
    log = workspace.log(run).read()
    started = next(e for e in log if e["type"] == "run_started")
    settings = dict(started["data"]["settings"])
    for e in log:
        if e["type"] == "settings_changed":
            settings.update(e["data"])
    return RunContext(
        run=run,
        issue=started["data"]["issue"],
        workspace=workspace,
        github=deps.github,
        agent=deps.agent,
        settings=Settings(**settings),
        policies=load_policies(deps.repo_root),
    )


# Reading a Run's progress from its Event Log, the system of record


def _stage_states(log: list[dict[str, Any]]) -> dict[str, str]:
    states = dict.fromkeys(STAGES, "pending")
    transitions = {
        "stage_started": "running",
        "stage_passed": "passed",
        "stage_failed": "failed",
        "invalidated": "invalidated",
    }
    for event in log:
        if event["type"] == "invalidated" and "lane" in event["data"]:
            continue  # one Lane redone; the Stage itself carries on
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
        elif e["type"] in ("approved", "rejected", "approval_withdrawn"):
            waiting[e["data"]["checkpoint"]] = False
    return [checkpoint for checkpoint, open_ in waiting.items() if open_]


def _pr_states(log: list[dict[str, Any]]) -> dict[int, str]:
    """Each PR the Run opened, by what it waits for (#75): checks pending, checks failed,
    awaiting merge, or nothing (merged, closed). A merge approval is the merge on GitHub."""
    states: dict[int, str] = {}
    for e in log:
        data = e.get("data") or {}
        checkpoint = str(data.get("checkpoint", ""))
        merge_pr = int(checkpoint.removeprefix("merge:")) if checkpoint.startswith("merge:") else 0
        if e["type"] in ("pr_opened", "pr_updated"):
            states[data["pr"]] = "checks pending"
        elif e["type"] == "checks_failed":
            states[data["pr"]] = "checks failed"
        elif e["type"] == "checks_passed":
            states[data["pr"]] = "merged" if data.get("via") == "merged" else "awaiting merge"
        elif e["type"] == "approved" and merge_pr:
            states[merge_pr] = "merged"
        elif e["type"] in ("rejected", "approval_withdrawn") and merge_pr:
            states[merge_pr] = "closed"
        elif e["type"] == "stage_passed" and e["stage"] == "pr" and "pr" in data:
            states[data["pr"]] = "merged"
    return states


def _waiting_checks(log: list[dict[str, Any]]) -> list[int]:
    return [pr for pr, state in _pr_states(log).items() if state == "checks pending"]


def _run_state(log: list[dict[str, Any]]) -> str:
    if any(e["type"] == "run_finished" for e in log):
        return "finished"
    last = [e["type"] for e in log if e["type"] in ("paused", "safe_stop", "resumed")]
    return {"paused": "paused", "safe_stop": "stopped"}.get(last[-1] if last else "", "active")


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
    if _run_state(log) == "paused":
        reason = next(e["data"].get("reason", "") for e in reversed(log) if e["type"] == "paused")
        print(f"Waiting for: orchestrate resume {run} (paused: {reason})")
    for pr in _waiting_checks(log) if _run_state(log) == "active" else []:
        print(f"Waiting for: required checks on PR #{pr}")
    prs = _pr_states(log)
    if prs:
        print("PRs: " + ", ".join(f"#{pr} {state}" for pr, state in prs.items()))
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
    try:
        policies = load_policies(deps.repo_root)
    except PolicyError as e:
        print(f"Refusing to start: {e}", file=sys.stderr)
        return USAGE_ERROR
    try:
        settings = load_settings(deps.repo_root)
    except ValueError as e:
        print(f"Refusing to start: {e}", file=sys.stderr)
        return USAGE_ERROR
    run = workspace.next_run_id()
    workspace.log(run).append(
        run=run,
        actor="engineer",
        type="run_started",
        data={"issue": issue.number, "settings": settings.as_dict(), "policies": policies.digest},
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
    tree = ctx.workspace.worktrees / waiting.get("tree", ctx.run)
    files = [tree / p for p in waiting.get("paths", [waiting["path"]])]
    if not all(f.is_file() for f in files):
        return True
    return content_hash("\n".join(f.read_text() for f in files)) != str(waiting["hash"])


# Model routing is re-read from settings.yaml on every resume, so a change to it (merged by a
# reviewed PR) reaches Runs already in flight; every other setting stays as the Run started.
ROUTING = ("model", "effort", "stage_models", "stage_efforts")


def _reroute(deps: Deps, workspace: Workspace, run: str) -> None:
    current = _context(deps, workspace, run).settings
    wanted = load_settings(deps.repo_root)
    changed = {k: getattr(wanted, k) for k in ROUTING if getattr(wanted, k) != getattr(current, k)}
    if changed:
        workspace.log(run).append(run=run, actor="engineer", type="settings_changed", data=changed)


def _resume(deps: Deps, workspace: Workspace, args: argparse.Namespace) -> int:
    run = args.run
    if args.cost_cap_run is not None:
        workspace.log(run).append(
            run=run,
            actor="engineer",
            type="settings_changed",
            data={"cost_cap_run_usd": args.cost_cap_run},
        )
    _reroute(deps, workspace, run)
    ctx = _context(deps, workspace, run)
    state = _run_state(workspace.log(run).read())
    if state == "finished":
        _print_run(workspace, run)
        return 0
    if state == "stopped":
        if "stop" in ctx.github.get_issue(ctx.issue).labels:
            print(f"Remove the stop label from Issue #{ctx.issue} first", file=sys.stderr)
            return REFUSED
        workspace.stop_flag(run).unlink(missing_ok=True)
    waiting = graph.waiting_on(ctx)
    if state in ("stopped", "paused") and not (waiting and waiting["kind"] == "paused"):
        ctx.event("resumed", None, actor="engineer")
    if waiting is None:
        if graph.pending_step(ctx):
            graph.continue_run(ctx)
            # A crash right after a pause left the Run between steps: continuing only reaches
            # the pause again, so retry it as this resume asked (#125).
            reached = graph.waiting_on(ctx)
            if state == "paused" and reached and reached["kind"] == "paused":
                graph.resume(ctx, {"action": "retry"})
    elif waiting["kind"] == "paused":
        graph.resume(ctx, {"action": "retry"})
    elif waiting["kind"] == "answer":
        comments = ctx.github.comments(ctx.issue)
        after = [c.id for c in comments].index(waiting["comment"]) + 1
        reply = next((c for c in comments[after:] if marker(run) not in c.body), None)
        if reply:
            graph.resume(ctx, {"body": reply.body, "by": reply.author})
    elif waiting["kind"] in ("checks", "merge", "lanes"):
        # the waiting node re-reads the PRs from GitHub. Not `{}`: LangGraph reads an empty dict
        # as a map of interrupt ids, which resumes nothing.
        graph.resume(ctx, {"recheck": True})
    elif waiting["kind"] == "approval":
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


def _replan(deps: Deps, workspace: Workspace, run: str) -> int:
    ctx = _context(deps, workspace, run)
    state = _run_state(ctx.log.read())
    if state == "finished":
        print(f"{run} is finished; a finished Run is never re-planned")
        _print_run(workspace, run)
        return 0
    if state == "stopped":
        print(f"{run} is stopped; resume it first with `orchestrate resume {run}`", file=sys.stderr)
        return REFUSED
    changes = graph.detect(ctx)
    if not changes:
        print("No inputs changed since they were recorded")
    else:
        graph.replan(ctx, changes)
    _print_run(workspace, run)
    return 0


def _stop(deps: Deps, workspace: Workspace, run: str) -> int:
    lock = workspace.lock(run)
    ctx = _context(deps, workspace, run)
    if lock.exists() and _alive(lock.read_text()):
        ctx.request_stop("orchestrate stop")
        print(f"Stop requested: {run} stops after its current step")
        return 0
    if _run_state(ctx.log.read()) in ("active", "paused"):
        ctx.request_stop("orchestrate stop")
        ctx.event("safe_stop", None, {"reason": "orchestrate stop"}, actor="engineer")
        ctx.mirror(f"⏹️ Stopped by the engineer. Continue with `orchestrate resume {run}`.")
    _print_run(workspace, run)
    return 0


def _alive(pid: str) -> bool:
    try:
        os.kill(int(pid), 0)
    except (ValueError, ProcessLookupError, PermissionError):
        return False
    return True


def _changed_message(waiting: dict[str, Any]) -> str:
    return (
        f"{waiting['path']} changed after it was submitted for approval "
        f"(submitted hash {waiting['hash'][:12]}); it can't be approved as it is now"
    )


def _decide(deps: Deps, workspace: Workspace, args: argparse.Namespace) -> int:
    if args.checkpoint.startswith("merge:"):
        pr = args.checkpoint.removeprefix("merge:")
        print(
            f"The orchestrator never merges: merge PR #{pr} on GitHub, "
            f"then run `orchestrate resume {args.run}`",
            file=sys.stderr,
        )
        return REFUSED
    ctx = _context(deps, workspace, args.run)
    if _run_state(ctx.log.read()) == "stopped":
        print(
            f"{args.run} is stopped; resume it first with `orchestrate resume {args.run}`",
            file=sys.stderr,
        )
        return REFUSED
    waiting = graph.waiting_on(ctx)
    if args.checkpoint.startswith("lane:"):
        key = args.checkpoint.removeprefix("lane:")
        if (
            args.command != "reject"
            or not waiting
            or waiting["kind"] != "paused"
            or key not in waiting.get("lanes", [waiting.get("lane")])
        ):
            print(f"{args.run} is not paused in Lane {key}", file=sys.stderr)
            return REFUSED
        graph.resume(
            ctx,
            {
                "action": "rollback",
                "lane": key,
                "by": ctx.github.current_user(),
                "reason": args.reason,
            },
        )
        _print_run(workspace, args.run)
        return 0
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


def _metrics(repo_root: Path) -> int:
    collected = metrics.regenerate(repo_root)
    for run, why in collected.ignored.items():
        print(f"Ignored {run}: {why}")
    for name, value in metrics.summary(collected) if collected.runs else []:
        print(f"{name}: {value}")
    print(f"Wrote delivery/metrics.md ({len(collected.runs)} finished Runs)")
    return 0


def _real_deps() -> Deps:
    top = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
    )
    root = Path(top.stdout.strip())
    settings = load_settings(root)
    return Deps(
        repo_root=root,
        github=GhCliGitHub(root, settings.board_owner, settings.board_number),
        agent=ClaudeAgent(),
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
    if args.command == "metrics":
        return _metrics(deps.repo_root)
    if not workspace.exists(args.run):
        print(f"Run {args.run} was not found", file=sys.stderr)
        return USAGE_ERROR
    if args.command == "resume":
        return _resume(deps, workspace, args)
    if args.command == "stop":
        return _stop(deps, workspace, args.run)
    if args.command == "replan":
        return _replan(deps, workspace, args.run)
    return _decide(deps, workspace, args)


if __name__ == "__main__":
    sys.exit(main())
