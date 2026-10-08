"""The Lanes Stage (spec 0002, stories 15-19, 25; ADR 0020): each ticket runs as a Lane, implement →
document → PR on its own branch and worktree from main, ending in a human merge.

Before any Lane starts, the Run's documents (spec, ADRs, ticket breakdown) go to main through a PR,
so every Lane branches from a main that already has them. Then the scheduler starts each Lane once
its blockers have merged, up to `max_parallel_lanes` at once. Lanes with agent work fan out as
parallel branches (`lane_work`); each runs to its next wait and returns to the join. Waits on CI,
merges, approvals and paused Lanes are held after the join, one interrupt at a time.
"""

import threading
from collections.abc import Callable
from typing import Any, Literal

from langgraph.types import Send, interrupt

from orchestrator import lineage
from orchestrator.agent import StepRequest
from orchestrator.approvals import gate_failed, record_gate, review_step
from orchestrator.commands import run, with_temporary_instance
from orchestrator.context import RunContext, RunPaused, RunStopped, marker
from orchestrator.gitops import (
    bring_in,
    changed_since_main,
    commit_all,
    lane_branch,
    push,
    remove_lane,
    run_branch,
    worktree,
)
from orchestrator.hashing import content_hash
from orchestrator.stages import roadmap_release
from orchestrator.state import RunState

STAGE = "lanes"
PREFIX = {"Feature": "feat", "Fix": "fix", "Test": "test", "Docs": "docs"}

IMPLEMENT = """\
Implement ticket #{issue} test-first: write a failing test at the agreed seam, make it pass, repeat.
Only what the acceptance criteria need. Use the vocabulary in CONTEXT.md and respect the ADRs.
Never edit the spec. If it has a gap you can't implement around, output spec_amendment: {{text
(the whole amended spec), reason}} instead, and the amendment goes to the engineer for approval."""
DOCUMENT = """\
Update the documents ticket #{issue} touched: the integration-testing plan's matrix and the README
where behaviour changed. Never edit the spec: close-out marks it implemented. Output docs_updated
(paths), or none needed."""

Lane = dict[str, Any]
_amendments = threading.Lock()  # Lanes run in parallel; amendments are numbered one at a time
PrOf = Callable[[RunState], int]

# A Lane's status. In flight from its start until it merges or is rolled back (ADR 0020).
IN_FLIGHT = (
    "working",
    "paused",
    "dependency",
    "amendment",
    "awaiting_amendment",
    "awaiting_checks",
    "awaiting_merge",
    "rolling_back",
)
FINISHED = ("merged", "rolled_back", "skipped", "removed")
# The Stage each phase of a working Lane belongs to
PHASE_STAGE = {"implement": "implement", "verify": "implement", "document": "document", "pr": "pr"}


def lane(state: RunState) -> Lane:
    """The Lane a parallel branch works on."""
    return next(ln for ln in state["lanes"] if ln["key"] == state["current"])


def with_status(state: RunState, status: str) -> list[Lane]:
    return [ln for ln in state["lanes"] if ln["status"] == status]


def lane_tree(ctx: RunContext, current: Lane) -> Any:
    return worktree(ctx.workspace, f"{ctx.run}-{current['issue']}", current["branch"])


# The Run's documents reach main first


def new_lane(ticket: dict[str, Any], issue: int) -> Lane:
    return {
        "key": ticket["key"],
        "issue": issue,
        "title": ticket["title"],
        "kind": ticket["kind"],
        "what": ticket["what"],
        "acceptance": ticket["acceptance"],
        "branch": lane_branch(issue, ticket["title"]),
        "blocked_by": list(ticket.get("blocked_by", [])),
        "ticket_hash": lineage.ticket_hash(ticket),
        "status": "pending",
        "phase": None,
        "attempts": 0,
        "feedback": None,
        "pr": None,
        "sha": None,
        "dependency_change": None,
        "dependency_approved": None,
        "amendment": None,
        "paused_stage": None,
        "rollback": None,
    }


class Begin:
    """Starts the Lanes Stage: the Run's documents go to main through a PR (when main doesn't have
    them yet), and the Lanes are set up from the approved tickets. After a Re-plan, existing Lanes
    are reconciled with the new breakdown instead (ADR 0011)."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        tree = ctx.workspace.worktrees / ctx.run
        sha = push(tree, run_branch(ctx.run))
        pr = self.docs_pr(state, sha) if changed_since_main(tree) else None
        at = "origin/main" if pr is None else f"origin/{run_branch(ctx.run)}"
        inputs = lineage.record(ctx, state, STAGE, at)
        ctx.event("stage_started", STAGE, {"inputs": inputs})
        lanes = (
            self.reconcile(state)
            if state.get("lanes")
            else [new_lane(t, state["published"][t["key"]]) for t in state["tickets"]]
        )
        update: RunState = {
            "lanes": lanes,
            "docs_pr": pr or state.get("docs_pr", 0),
            "docs_sha": sha,
            "docs_merged": pr is None,
            "docs_prs": [*state.get("docs_prs", []), *([pr] if pr else [])],
            "lineage": {**state.get("lineage", {}), STAGE: inputs},
        }
        return update

    def docs_pr(self, state: RunState, sha: str) -> int:
        ctx, spec = self.ctx, state["spec"]
        body = (
            f"Spec, ADRs and the ticket breakdown for #{ctx.issue}, from Run {ctx.run}.\n\n"
            f"- Spec: `{spec['path']}`\n"
            + "".join(f"- ADR: `{a['path']}`\n" for a in state.get("adrs_accepted", []))
            + f"- Tickets: {', '.join(f'#{n}' for n in state['published'].values())}\n\n"
            "Each was approved at its Approval Checkpoint; merging this PR lets the Lanes start."
        )
        pr = ctx.github.create_pr(
            run_branch(ctx.run),
            "main",
            f"docs: spec, ADRs and tickets for #{ctx.issue} ({ctx.run})",
            body,
        )
        ctx.event("pr_opened", STAGE, {"lane": "docs", "pr": pr, "sha": sha})
        ctx.mirror(f"Stage **lanes** started. First, the Run's documents go to main: PR #{pr}.")
        return pr

    def reconcile(self, state: RunState) -> list[Lane]:
        """Keep Lanes whose ticket is unchanged, redo unmerged Lanes whose ticket changed, follow up
        merged ones with a new ticket (merged work is never rewritten), and add new tickets."""
        ctx = self.ctx
        old = {ln["key"]: ln for ln in state["lanes"]}
        lanes: list[Lane] = []
        for t in state["tickets"]:
            current = old.pop(t["key"], None)
            if current is None:
                lanes.append(new_lane(t, state["published"][t["key"]]))
            elif current["ticket_hash"] == lineage.ticket_hash(t):
                lanes.append(current)
            elif current["status"] == "merged":
                lanes += [current, self.follow_up(state, current, t)]
            else:
                if current["status"] != "pending":
                    discard(ctx, current)
                    invalidated(ctx, current, "its ticket changed")
                lanes.append(new_lane(t, current["issue"]))
        for current in old.values():
            if current["status"] in FINISHED:
                lanes.append(current)
                continue
            discard(ctx, current)
            invalidated(ctx, current, "its ticket was removed")
            lanes.append({**current, "status": "removed"})
        return lanes

    def follow_up(self, state: RunState, merged: Lane, ticket: dict[str, Any]) -> Lane:
        ctx = self.ctx
        n = 1 + sum(1 for ln in state["lanes"] if ln["key"].startswith(f"{merged['key']}-f"))
        key = f"{merged['key']}-f{n}"
        title = f"Follow-up to #{merged['issue']}: {ticket['title']}"
        body = (
            f"Follows #{merged['issue']} (merged in PR #{merged['pr']}), whose ticket changed in a "
            f"re-plan of Run {ctx.run} (Issue #{ctx.issue}). Merged work is never rewritten.\n\n"
            f"## What to build\n\n{ticket['what']}\n\n## Acceptance criteria\n\n"
            + "\n".join(f"- [ ] {a}" for a in ticket["acceptance"])
            + "\n"
        )
        release = roadmap_release(ctx.workspace, state.get("roadmap_item"))
        milestone = ctx.github.milestone_for_release(release) if release else None
        issue = ctx.github.create_issue(title, body, ("ready-for-agent",), milestone)
        release_field = {"Release": release} if release else {}
        ctx.github.add_to_board(issue, {"Status": "Todo", **release_field, "Kind": ticket["kind"]})
        ctx.event(
            "follow_up_created", STAGE, {"key": key, "issue": issue, "follows": merged["issue"]}
        )
        ctx.mirror(f"Follow-up #{issue} opened for merged #{merged['issue']}: its ticket changed.")
        return new_lane({**ticket, "key": key, "title": title}, issue)


def discard(ctx: RunContext, current: Lane) -> None:
    """Close a Lane's open PR (withdrawing its merge approval); delete its branch and worktree."""
    pr = current["pr"]
    if pr is not None and ctx.github.pr(pr).state == "open":
        ctx.github.close_pr(pr)
        ctx.event("approval_withdrawn", STAGE, {"checkpoint": f"merge:{pr}"})
    remove_lane(ctx.workspace, f"{ctx.run}-{current['issue']}", current["branch"])


def invalidated(ctx: RunContext, current: Lane, reason: str) -> None:
    ctx.event("invalidated", STAGE, {"stage": STAGE, "lane": current["key"], "reason": reason})
    ctx.mirror(f"Lane **{current['key']}** is redone: {reason}.")


def checks_outcome(ctx: RunContext, pr: int, sha: str) -> tuple[str, str]:
    """("passed" | "failed" | "pending", failure output) for the required checks on `sha`."""
    if ctx.github.pr(pr).state == "merged":  # branch protection only merges on green checks
        ctx.event("checks_passed", STAGE, {"pr": pr, "sha": sha, "via": "merged"})
        return "passed", ""
    checks = ctx.github.pr_checks(pr, sha)
    failed = [c for c in checks if c.state == "failure"]
    if failed:
        ctx.event(
            "checks_failed", STAGE, {"pr": pr, "sha": sha, "failed": [c.name for c in failed]}
        )
        return "failed", "\n".join(f"{c.name}: {c.details}" for c in failed)
    if checks and all(c.state == "success" for c in checks):
        ctx.event("checks_passed", STAGE, {"pr": pr, "sha": sha})
        return "passed", ""
    return "pending", ""


def request_merge(ctx: RunContext, pr: int) -> None:
    ctx.event("approval_requested", STAGE, {"checkpoint": f"merge:{pr}", "pr": pr})
    ctx.mirror(
        f"✅ Approval needed: **merge:{pr}**: PR #{pr} passed its required checks. Review and "
        "merge it on GitHub (the orchestrator never merges), "
        f"then run `orchestrate resume {ctx.run}`."
    )


def merge_outcome(ctx: RunContext, number: int) -> str | None:
    """Merge approval means a human merged the PR on GitHub (spec 0002, story 25)."""
    pr = ctx.github.pr(number)
    checkpoint = f"merge:{number}"
    if pr.state == "merged":
        ctx.event(
            "approved",
            STAGE,
            {"checkpoint": checkpoint, "by": pr.merged_by, "channel": "github", "hash": None},
            actor="engineer",
        )
        ctx.mirror(f"PR #{number} merged by @{pr.merged_by}.")
        return "merged"
    if pr.state == "closed":
        ctx.event(
            "rejected",
            STAGE,
            {
                "checkpoint": checkpoint,
                "by": None,
                "channel": "github",
                "reason": f"PR #{number} was closed without merging",
            },
        )
        return "closed"
    return None


class AwaitChecks:
    """Waits until every required check on the documents PR's latest commit has finished."""

    def __init__(self, ctx: RunContext, pr_of: PrOf, sha_of: Callable[[RunState], str]) -> None:
        self.ctx, self.pr_of, self.sha_of = ctx, pr_of, sha_of

    def __call__(self, state: RunState) -> RunState:
        pr = self.pr_of(state)
        ci, output = checks_outcome(self.ctx, pr, self.sha_of(state))
        if ci == "pending":
            interrupt({"kind": "checks", "pr": pr})
            return {"ci": "recheck"}
        return {"ci": ci, "ci_output": output}


class RequestMerge:
    def __init__(self, ctx: RunContext, pr_of: PrOf) -> None:
        self.ctx, self.pr_of = ctx, pr_of

    def __call__(self, state: RunState) -> RunState:
        request_merge(self.ctx, self.pr_of(state))
        return {}


class AwaitMerge:
    def __init__(self, ctx: RunContext, pr_of: PrOf) -> None:
        self.ctx, self.pr_of = ctx, pr_of

    def __call__(self, state: RunState) -> RunState:
        pr = self.pr_of(state)
        outcome = merge_outcome(self.ctx, pr)
        if outcome is None:
            interrupt({"kind": "merge", "pr": pr})
            return {"merge": "recheck"}
        return {"merge": outcome, "docs_merged": outcome == "merged"}


class DocsChecksFailed:
    """The documents PR has no agent to fix it: pause for the engineer."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        reason = f"required checks failed on the documents PR #{state['docs_pr']}"
        problems = [f"{reason}:\n{state.get('ci_output', '')}"]
        record_gate(self.ctx, STAGE, "required checks pass", problems)
        self.ctx.event("stage_failed", STAGE, {"problems": problems})
        self.ctx.event("paused", STAGE, {"reason": reason})
        self.ctx.mirror(
            f"⏸️ Paused: {reason}. Fix it on the PR, then `orchestrate resume {self.ctx.run}`."
        )
        return {"paused": True}


# The scheduler: polls the waiting Lanes, skips and starts Lanes, then fans out the agent work


def lane_gate_failed(
    ctx: RunContext, stage: str, what: str, current: Lane, problems: list[str], retry: str
) -> Lane:
    """A Lane's Exit Gate failed: retry `retry` with the problems, or pause the Lane."""
    result = gate_failed(
        ctx, stage, what, {"attempts": current["attempts"]}, problems, current["key"]
    )
    current = {**current, "attempts": result["attempts"], "phase": retry, "status": "working"}
    if result.get("paused"):
        return {**current, "status": "paused", "paused_stage": stage}
    return {**current, "feedback": result["feedback"]}


class Schedule:
    """Runs after every join. Reads each waiting Lane's PR, skips the Lanes whose blockers didn't
    merge, and starts each Lane whose blockers all merged, up to `max_parallel_lanes` in flight."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        lanes = {ln["key"]: dict(ln) for ln in state["lanes"]}
        for key, current in lanes.items():
            if current["status"] == "awaiting_checks":
                lanes[key] = current = self.checks(current)
            if current["status"] == "awaiting_merge":
                lanes[key] = self.merge(current)
            if current["status"] == "awaiting_amendment":
                lanes[key] = self.amendment(current)
        self.skip(lanes)
        self.start(lanes)
        return {"lanes": list(lanes.values())}

    def checks(self, current: Lane) -> Lane:
        ctx, pr = self.ctx, current["pr"]
        if ctx.github.pr(pr).state != "open":
            return {**current, "status": "awaiting_merge"}
        ci, output = checks_outcome(ctx, pr, current["sha"])
        if ci == "pending":
            return current
        if ci == "failed":
            problems = [f"Required checks failed on PR #{pr}:\n{output}"]
            record_gate(ctx, "pr", "required checks pass", problems)
            return lane_gate_failed(ctx, "pr", "required checks", current, problems, "implement")
        request_merge(ctx, pr)
        return {**current, "status": "awaiting_merge"}

    def merge(self, current: Lane) -> Lane:
        ctx, pr = self.ctx, current["pr"]
        outcome = merge_outcome(ctx, pr)
        if outcome == "merged":
            record_gate(ctx, "pr", "required checks pass", [])
            ctx.event("stage_passed", "pr", {"lane": current["key"], "pr": pr})
            ctx.event("lane_finished", "pr", {"lane": current["key"], "outcome": "merged"})
            return {**current, "status": "merged"}
        if outcome == "closed":
            reason = f"PR #{pr} was closed without merging"
            return {**current, "status": "rolling_back", "rollback": {"reason": reason, "by": None}}
        return current

    def amendment(self, current: Lane) -> Lane:
        """A merged amendment reached main (a Re-plan has run, if it changed anything): the Lane
        continues on the amended spec. A closed one sends it back to implement."""
        ctx, amendment = self.ctx, current["amendment"]
        pr = ctx.github.pr(amendment["pr"])
        if pr.state == "open":
            return current
        if pr.state == "merged":
            bring_in(
                lane_tree(ctx, current),
                "origin/main",
                f"chore: bring spec amendment {amendment['n']} from main (#{current['issue']})",
            )
            feedback = f"Spec amendment {amendment['n']} merged; continue on the amended spec."
        else:
            feedback = f"Spec amendment {amendment['n']}'s PR #{pr.number} was closed unmerged."
        return {**current, "status": "working", "phase": "implement", "feedback": feedback}

    def skip(self, lanes: dict[str, Lane]) -> None:
        """Skip every pending Lane blocked by a Lane that will not merge, transitively."""
        changed = True
        while changed:
            changed = False
            failed = {k for k, ln in lanes.items() if ln["status"] in ("rolled_back", "skipped")}
            for key, current in lanes.items():
                blockers = [b for b in current["blocked_by"] if b in failed]
                if current["status"] != "pending" or not blockers:
                    continue
                data = {"lane": key, "issue": current["issue"], "blocked_by": blockers}
                self.ctx.event("lane_skipped", "implement", data)
                self.ctx.mirror(
                    f"Lane **{key}** (#{current['issue']}) skipped: "
                    f"it is blocked by {', '.join(blockers)}, which did not merge."
                )
                lanes[key] = {**current, "status": "skipped"}
                changed = True

    def start(self, lanes: dict[str, Lane]) -> None:
        ctx = self.ctx
        limit = max(1, ctx.settings.max_parallel_lanes)
        in_flight = sum(1 for ln in lanes.values() if ln["status"] in IN_FLIGHT)
        for key, current in lanes.items():
            if in_flight >= limit:
                return
            ready = all(lanes[b]["status"] == "merged" for b in current["blocked_by"])
            if current["status"] != "pending" or not ready:
                continue
            lane_tree(ctx, current)  # branched from main as it is now, with its blockers merged
            data = {"lane": key, "issue": current["issue"]}
            ctx.event("lane_started", "implement", data)
            ctx.event("stage_started", "implement", data)
            ctx.mirror(f"Lane **{key}** started: #{current['issue']} {current['title']}.")
            lanes[key] = {**current, "status": "working", "phase": "implement", "attempts": 0}
            in_flight += 1


def after_schedule(state: RunState) -> list[Send] | str:
    """Fan out every Lane with agent work; else hold one wait, or join for release readiness."""
    if with_status(state, "rolling_back"):
        return "rollback"
    working = with_status(state, "working")
    if working:
        return [Send("lane_work", {**state, "current": ln["key"]}) for ln in working]
    if with_status(state, "paused"):
        return "lane_paused"
    if with_status(state, "dependency"):
        return "dependency_request_approval"
    if with_status(state, "amendment"):
        return "amendment_request_approval"
    if all(ln["status"] in FINISHED for ln in state["lanes"]):
        return "lanes_done"
    return "lanes_wait"


class LaneWork:
    """One Lane's agent work, as a parallel branch: from its current phase to its next wait (its
    PR's checks, a dependency approval, or a pause). It never interrupts, and a Safe-stop or a
    policy pause is recorded on the Lane rather than raised, so other branches are not disturbed."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx
        self.steps: dict[str, Callable[[RunState, Lane], Lane]] = {
            "implement": self.implement,
            "verify": self.verify,
            "document": self.document,
            "pr": self.open_pr,
        }

    def __call__(self, state: RunState) -> RunState:
        ctx, current = self.ctx, dict(lane(state))
        try:
            while current["status"] == "working":
                ctx.check_boundary(PHASE_STAGE[current["phase"]])
                current = self.steps[current["phase"]](state, current)
        except RunStopped as stopped:
            ctx.request_stop(stopped.reason)  # the join's boundary stops the Run
        except RunPaused as paused:
            ctx.event("paused", paused.stage, {"reason": paused.reason, "lane": current["key"]})
            ctx.mirror(
                f"⏸️ Paused in **{paused.stage}**: {paused.reason}. "
                f"Resume with `orchestrate resume {ctx.run}`, or roll Lane {current['key']} back "
                f'with `orchestrate reject {ctx.run} lane:{current["key"]} --reason "…"`.'
            )
            current = {**current, "status": "paused", "paused_stage": paused.stage}
        return {"lanes": [current]}

    def implement(self, state: RunState, current: Lane) -> Lane:
        ctx = self.ctx
        tree = lane_tree(ctx, current)
        result = ctx.call_agent(
            StepRequest(
                run=ctx.run,
                stage="implement",
                instructions=IMPLEMENT.format(issue=current["issue"]),
                workspace=tree,
                context={
                    "ticket": {
                        k: current[k] for k in ("key", "issue", "title", "what", "acceptance")
                    },
                    "spec": state["spec"],
                    "adrs": state.get("adrs_accepted", []),
                    "feedback": current["feedback"],
                },
                budget_usd=ctx.settings.cost_cap_step_usd,
            )
        )
        if "spec_amendment" in result.output:
            return self.amend(state, current, result.output["spec_amendment"])
        problems, _ = review_step(ctx, "implement", tree, dependencies_allowed=True)
        if problems:
            record_gate(ctx, "implement", "policy", problems)
            return lane_gate_failed(ctx, "implement", "policy", current, problems, "implement")
        prefix = PREFIX.get(current["kind"], "feat")
        commit_all(tree, f"{prefix}: {current['title']} (#{current['issue']})")
        manifests = [
            m for m in changed_since_main(tree) if m in ctx.policies.raw["dependency_manifests"]
        ]
        if manifests:
            digest = manifests_hash(tree, manifests)
            if current["dependency_approved"] != digest:
                push(tree, current["branch"])
                change = {"manifests": manifests, "hash": digest}
                return {**current, "status": "dependency", "dependency_change": change}
        return {**current, "phase": "verify", "feedback": None}

    def amend(self, state: RunState, current: Lane, amendment: dict[str, Any]) -> Lane:
        """The agent found a gap in the spec: the amended spec goes on its own branch from main,
        and the Lane waits for its approval. Nothing approved changes yet (ADR 0011)."""
        ctx, path = self.ctx, state["spec"]["path"]
        with _amendments:
            n = 1 + len([e for e in ctx.log.read() if e["type"] == "amendment_raised"])
            branch = f"{run_branch(ctx.run)}-amendment-{n}"
            tree = worktree(ctx.workspace, f"{ctx.run}-amendment-{n}", branch)
            text = str(amendment["text"])
            (tree / path).write_text(text)
            commit_all(tree, f"docs: spec amendment {n} for #{ctx.issue} ({ctx.run})")
            push(tree, branch)
            record = {
                "n": n,
                "branch": branch,
                "path": path,
                "hash": content_hash(text),
                "reason": str(amendment.get("reason", "")),
                "pr": None,
            }
            ctx.event("amendment_raised", "implement", {"lane": current["key"], **record})
        return {**current, "status": "amendment", "amendment": record}

    def verify(self, state: RunState, current: Lane) -> Lane:
        """The implement Exit Gate: the verify command, plus browser checks on a temporary
        instance when the web page changed."""
        ctx, s = self.ctx, self.ctx.settings
        tree = lane_tree(ctx, current)
        outcomes = [run(s.verify_command, tree)]
        web = tuple(p for p in s.web_paths.split(",") if p)
        if outcomes[0].ok and any(f.startswith(web) for f in changed_since_main(tree)):
            outcomes.append(
                with_temporary_instance(
                    s.app_start_command, s.browser_check_command, s.app_stop_command, tree
                )
            )
        problems = [o.problem() for o in outcomes if not o.ok]
        record_gate(ctx, "implement", "verify passes", problems)
        if problems:
            return lane_gate_failed(ctx, "implement", "verify", current, problems, "implement")
        ctx.event("stage_passed", "implement", {"lane": current["key"]})
        return {**current, "phase": "document", "feedback": None}

    def document(self, state: RunState, current: Lane) -> Lane:
        ctx = self.ctx
        tree = lane_tree(ctx, current)
        ctx.event("stage_started", "document", {"lane": current["key"]})
        result = ctx.call_agent(
            StepRequest(
                run=ctx.run,
                stage="document",
                instructions=DOCUMENT.format(issue=current["issue"]),
                workspace=tree,
                context={
                    "ticket": {k: current[k] for k in ("key", "issue", "title")},
                    "spec": state["spec"],
                    "changed_files": changed_since_main(tree),
                    "feedback": current["feedback"],
                },
                budget_usd=ctx.settings.cost_cap_step_usd,
            )
        )
        problems, _ = review_step(ctx, "document", tree)
        if problems:
            record_gate(ctx, "document", "policy", problems)
            return lane_gate_failed(ctx, "document", "policy", current, problems, "document")
        commit_all(tree, f"docs: {current['title']} (#{current['issue']})")
        docs = list(result.output.get("docs_updated", []))
        ctx.event("stage_passed", "document", {"lane": current["key"], "docs_updated": docs})
        return {**current, "phase": "pr", "feedback": None, "attempts": 0}

    def open_pr(self, state: RunState, current: Lane) -> Lane:
        ctx = self.ctx
        sha = push(lane_tree(ctx, current), current["branch"])
        pr = current["pr"]
        if pr is None:
            prefix = PREFIX.get(current["kind"], "feat")
            spec_url = ctx.github.file_url("main", state["spec"]["path"])
            pr = ctx.github.create_pr(
                current["branch"],
                "main",
                f"{prefix}: {current['title']} (#{current['issue']})",
                f"Closes #{current['issue']}\n\nRun {ctx.run} (Issue #{ctx.issue}) · "
                f"[spec]({spec_url})\n\n## Acceptance criteria\n\n"
                + "\n".join(f"- [x] {a}" for a in current["acceptance"]),
            )
            ctx.event("pr_opened", "pr", {"lane": current["key"], "pr": pr, "sha": sha})
            ctx.mirror(f"Lane **{current['key']}**: PR #{pr} opened; waiting for its checks.")
        else:
            ctx.event("pr_updated", "pr", {"lane": current["key"], "pr": pr, "sha": sha})
        return {**current, "pr": pr, "sha": sha, "phase": None, "status": "awaiting_checks"}


class Join:
    """Where the parallel branches meet: the next step runs only once every branch has returned."""

    def __call__(self, state: RunState) -> RunState:
        return {}


# The waits held after the join


class LanesWait:
    """Every Lane in flight is waiting on its PR: one interrupt lists them all."""

    def __call__(self, state: RunState) -> RunState:
        waits = [
            {"lane": ln["key"], "pr": ln["pr"], "for": ln["status"].removeprefix("awaiting_")}
            for ln in state["lanes"]
            if ln["status"] in ("awaiting_checks", "awaiting_merge")
        ] + [
            {"lane": ln["key"], "pr": ln["amendment"]["pr"], "for": "amendment"}
            for ln in with_status(state, "awaiting_amendment")
        ]
        interrupt({"kind": "lanes", "waits": waits})
        return {}


class LanePaused:
    """Waits for the engineer about the paused Lanes: `resume` retries them all, and
    `reject lane:<key>` rolls one back."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        paused = with_status(state, "paused")
        first = paused[0]
        value: dict[str, Any] = interrupt(
            {
                "kind": "paused",
                "stage": first["paused_stage"],
                "lane": first["key"],
                "lanes": [ln["key"] for ln in paused],
            }
        )
        if value.get("action") == "rollback":
            target = next(ln for ln in paused if ln["key"] == value.get("lane", first["key"]))
            rollback = {"reason": value["reason"], "by": value["by"]}
            return {"lanes": [{**target, "status": "rolling_back", "rollback": rollback}]}
        for ln in paused:
            self.ctx.event("resumed", ln["paused_stage"], {"lane": ln["key"]}, actor="engineer")
        return {
            "lanes": [
                {**ln, "status": "working", "attempts": 0, "paused_stage": None} for ln in paused
            ]
        }


def dependency_lane(state: RunState) -> Lane:
    return with_status(state, "dependency")[0]


def manifests_hash(tree: Any, manifests: list[str]) -> str:
    return content_hash("\n".join((tree / m).read_text() for m in manifests))


def describe_dependency(state: RunState) -> tuple[str, str, str, str]:
    current = dependency_lane(state)
    change = current["dependency_change"]
    listed = ", ".join(f"`{m}`" for m in change["manifests"])
    return (
        f"dependency:{current['key']}",
        change["manifests"][0],
        change["hash"],
        f"\n\nLane {current['key']} changes dependency manifests: {listed}. "
        "Dependency changes always get a human review.",
    )


def dependency_location(state: RunState) -> tuple[str, str, list[str]]:
    current = dependency_lane(state)
    return (
        current["branch"],
        f"{state['run']}-{current['issue']}",
        current["dependency_change"]["manifests"],
    )


class DependencyDecided:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        decision, current = state["decision"], dependency_lane(state)
        current = {**current, "status": "working", "dependency_change": None}
        if not decision["approved"]:
            feedback = f"{decision['checkpoint']} rejected: {decision['reason']}"
            return {
                "lanes": [{**current, "phase": "implement", "feedback": feedback, "attempts": 0}]
            }
        approved = dependency_lane(state)["dependency_change"]["hash"]
        return {"lanes": [{**current, "phase": "verify", "dependency_approved": approved}]}


def amendment_lane(state: RunState) -> Lane:
    return with_status(state, "amendment")[0]


def describe_amendment(state: RunState) -> tuple[str, str, str, str]:
    current = amendment_lane(state)
    a = current["amendment"]
    return (
        f"amendment-{a['n']}",
        a["path"],
        a["hash"],
        f"\n\nLane {current['key']} found a gap in the spec: {a['reason']}\n\nApproving opens a PR "
        "to main; merging it re-plans the Run from the spec.",
    )


def amendment_location(state: RunState) -> tuple[str, str, list[str]]:
    a = amendment_lane(state)["amendment"]
    return a["branch"], f"{state['run']}-amendment-{a['n']}", [a["path"]]


class AmendmentDecided:
    """Approved: the amendment goes to main as a PR. Rejected: the Lane goes back to implement."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx, decision, current = self.ctx, state["decision"], amendment_lane(state)
        a = current["amendment"]
        if not decision["approved"]:
            remove_lane(ctx.workspace, f"{ctx.run}-amendment-{a['n']}", a["branch"])
            feedback = f"{decision['checkpoint']} rejected: {decision['reason']}"
            working = {"status": "working", "phase": "implement", "attempts": 0}
            return {"lanes": [{**current, **working, "feedback": feedback, "amendment": None}]}
        pr = ctx.github.create_pr(
            a["branch"],
            "main",
            f"docs: spec amendment {a['n']} for #{ctx.issue} ({ctx.run})",
            f"Lane {current['key']} (#{current['issue']}) of Run {ctx.run} found a gap in the "
            f"spec: {a['reason']}\n\nApproved as `amendment-{a['n']}` (hash `{a['hash'][:12]}`). "
            "Merging this PR changes the spec on main, and the Run re-plans from it.",
        )
        ctx.event("amendment_pr_opened", "implement", {"lane": current["key"], "pr": pr})
        ctx.mirror(f"Spec amendment {a['n']}: PR #{pr}. Merge it on GitHub to re-plan the Run.")
        return {
            "lanes": [{**current, "status": "awaiting_amendment", "amendment": {**a, "pr": pr}}]
        }


class Rollback:
    """Undo a Lane: close its PR, delete its branch and worktree, return its ticket to Ready."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        return {"lanes": [self.roll_back(ln) for ln in with_status(state, "rolling_back")]}

    def roll_back(self, current: Lane) -> Lane:
        ctx = self.ctx
        reason, by = current["rollback"]["reason"], current["rollback"]["by"]
        pr = current["pr"]
        discard(ctx, current)
        ctx.github.add_to_board(current["issue"], {"Status": "Todo"})
        ctx.github.add_comment(
            current["issue"],
            f"{marker(ctx.run)}\nLane {current['key']} of Run {ctx.run} was rolled back: {reason}. "
            "This ticket is back in Ready (Todo).",
        )
        data = {"lane": current["key"], "issue": current["issue"], "pr": pr, "reason": reason}
        ctx.event(
            "lane_rolled_back",
            "implement",
            {**data, "by": by},
            actor="engineer" if by else "orchestrator",
        )
        ctx.event("lane_finished", "implement", {"lane": current["key"], "outcome": "rolled_back"})
        ctx.mirror(
            f"↩️ Lane **{current['key']}** rolled back: {reason}. Lanes that merged are kept."
        )
        return {**current, "status": "rolled_back", "rollback": None}


class LanesDone:
    """The join is complete: every Lane merged, was rolled back, or was skipped."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        by_status: dict[str, list[str]] = {"merged": [], "rolled_back": [], "skipped": []}
        for ln in state["lanes"]:
            by_status.setdefault(ln["status"], []).append(ln["key"])
        prs = [ln["pr"] for ln in state["lanes"] if ln["status"] == "merged"]
        self.ctx.event("stage_passed", STAGE, {"artifacts": {"prs": prs}, **by_status})
        return {}


def docs_pr(state: RunState) -> int:
    return state["docs_pr"]


def docs_sha(state: RunState) -> str:
    return state["docs_sha"]


def after_begin(state: RunState) -> Literal["docs_checks", "schedule"]:
    return "schedule" if state.get("docs_merged") else "docs_checks"


def after_docs_checks(state: RunState) -> Literal["recheck", "request_merge", "stop"]:
    if state["ci"] == "recheck":
        return "recheck"
    return "request_merge" if state["ci"] == "passed" else "stop"


def after_merge(state: RunState) -> Literal["recheck", "next", "closed"]:
    if state["merge"] == "recheck":
        return "recheck"
    return "next" if state["merge"] == "merged" else "closed"
