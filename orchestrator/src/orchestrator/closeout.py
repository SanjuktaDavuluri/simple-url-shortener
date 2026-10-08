"""Release readiness and close-out (spec 0002, stories 19-20, 51).

Release readiness checks end-to-end traceability after every Lane has merged. Close-out writes the
Run's report, finishes its Event Log, and opens one PR that commits both under delivery/runs/.
"""

import re
import shutil
from collections import Counter
from typing import Any

from orchestrator import metrics
from orchestrator.context import RunContext
from orchestrator.gitops import commit_all, push, worktree
from orchestrator.state import RunState

Event = dict[str, Any]


class ReleaseReadiness:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        ctx.event("stage_started", "release_readiness")
        problems: list[str] = []
        merged = [ln for ln in state["lanes"] if ln["status"] == "merged"]
        docs = state.get("docs_prs", [state["docs_pr"]])
        prs = [
            *((n, ctx.issue, None) for n in docs),
            *((ln["pr"], ln["issue"], ln) for ln in merged),
        ]
        for number, issue, current in prs:
            pr = ctx.github.pr(number)
            if pr.state != "merged":
                problems.append(f"PR #{number} is not merged")
            if current is not None and f"Closes #{issue}" not in pr.body:
                problems.append(f"PR #{number} does not say Closes #{issue}")
            for subject in pr.commits:
                if f"#{issue}" not in subject:
                    problems.append(f"PR #{number}: commit '{subject}' does not reference #{issue}")
        for e in ctx.log.read():
            if e["type"] == "approved" and not e["data"].get("by"):
                problems.append(f"Approval of {e['data']['checkpoint']} has no recorded approver")
        ctx.event(
            "gate_result",
            "release_readiness",
            {"gate": "traceability is complete", "passed": not problems, "problems": problems},
        )
        if problems:
            listing = "\n".join(f"- {p}" for p in problems)
            ctx.event("stage_failed", "release_readiness", {"problems": problems})
            ctx.event("paused", "release_readiness", {"reason": "traceability is incomplete"})
            ctx.mirror(
                f"⏸️ Paused: release readiness found gaps in traceability:\n{listing}\n\n"
                f"Fix them, then `orchestrate resume {ctx.run}`."
            )
            return {"paused": True}
        ctx.event("stage_passed", "release_readiness")
        return {}


def report(run: str, events: list[Event]) -> str:
    started = events[0]
    issue = started["data"]["issue"]
    title = next(
        (
            e["data"]["artifacts"]["issue"]["title"]
            for e in events
            if e["type"] == "stage_passed" and e["stage"] == "intake"
        ),
        "",
    )
    timeline = "\n".join(
        f"| {e['ts']} | {e['stage']} | {e['type'].removeprefix('stage_')} |"
        for e in events
        if e["type"] in ("stage_started", "stage_passed", "stage_failed")
    )
    gates = Counter((e["stage"], e["data"]["passed"]) for e in events if e["type"] == "gate_result")
    gate_rows = "\n".join(
        f"| {stage} | {gates[(stage, True)]} | {gates[(stage, False)]} |"
        for stage in dict.fromkeys(s for s, _ in gates)
    )
    decisions = "\n".join(
        f"| {e['data']['checkpoint']} | {e['type']} | @{e['data'].get('by')} "
        f"| {e['data'].get('channel')} "
        f"| {e['data'].get('reason', '')} |"
        for e in events
        if e["type"] in ("approved", "rejected")
    )
    prs = "\n".join(
        f"| {e['data']['lane']} | #{e['data']['pr']} |" for e in events if e["type"] == "pr_opened"
    )
    undone = "\n".join(
        [
            f"| {e['data']['lane']} | #{e['data']['issue']} | Rolled back: {e['data']['reason']} |"
            for e in events
            if e["type"] == "lane_rolled_back"
        ]
        + [
            f"| {e['data']['lane']} | #{e['data']['issue']} | Skipped: blocked by "
            f"{', '.join(e['data']['blocked_by'])} |"
            for e in events
            if e["type"] == "lane_skipped"
        ]
    )
    calls = [e for e in events if e["type"] == "agent_call"]
    cost = sum(e["data"]["cost_usd"] for e in calls)
    retries = sum(1 for e in events if e["type"] == "gate_result" and not e["data"]["passed"])
    return f"""# Run {run}: #{issue} {title}

Started {started["ts"]} · finished {events[-1]["ts"]} · {len(events)} events
· verify with `orchestrate verify {run}`

## Summary

| Agent calls | Cost | Failed gates (retried or paused) |
|---|---|---|
| {len(calls)} | ${cost:.2f} | {retries} |

## Timeline

| Time | Stage | |
|---|---|---|
{timeline}

## Exit Gates

| Stage | Passed | Failed |
|---|---|---|
{gate_rows}

## Approvals

| Checkpoint | Decision | By | Channel | Reason |
|---|---|---|---|---|
{decisions}

## Pull requests

| Lane | PR |
|---|---|
{prs}

## Rolled back and skipped Lanes

| Lane | Ticket | What happened |
|---|---|---|
{undone or "| — | — | none |"}
"""


class CloseOut:
    """Commits the report and the finished Event Log, and marks the spec implemented, in one PR."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        ctx.event("stage_started", "close_out")
        branch = f"docs/run-{ctx.run}-close-out"
        tree = worktree(ctx.workspace, f"{ctx.run}-close-out", branch)
        spec = tree / state["spec"]["path"]
        spec.write_text(
            re.sub(
                r"^status:\s*\S+",
                "status: implemented",
                spec.read_text(),
                count=1,
                flags=re.MULTILINE,
            )
        )
        ctx.event("stage_passed", "close_out")
        rolled_back = [ln["key"] for ln in state["lanes"] if ln["status"] == "rolled_back"]
        skipped = [ln["key"] for ln in state["lanes"] if ln["status"] == "skipped"]
        outcome: dict[str, object] = {"outcome": "delivered"}
        if rolled_back or skipped:
            outcome = {
                "outcome": "partially delivered",
                "rolled_back": rolled_back,
                "skipped": skipped,
            }
        ctx.event("run_finished", None, outcome)
        target = tree / "delivery" / "runs" / ctx.run
        target.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ctx.log.path, target / "events.jsonl")
        (target / "report.md").write_text(report(ctx.run, ctx.log.read()))
        metrics.regenerate(tree)  # the committed Runs on main, plus this one
        commit_all(
            tree, f"docs: close out {ctx.run}: report, Event Log, spec implemented (#{ctx.issue})"
        )
        push(tree, branch)
        pr = ctx.github.create_pr(
            branch,
            "main",
            f"docs: close out {ctx.run} (#{ctx.issue})",
            f"Run {ctx.run} for #{ctx.issue} is finished. This commits its report and Event Log "
            f"under `delivery/runs/{ctx.run}/`, regenerates `delivery/metrics.md` and marks the "
            "spec implemented.",
        )
        ctx.mirror(f"🏁 Run finished. Close-out PR #{pr}: report, Event Log and spec status.")
        return {"close_out_pr": pr}
