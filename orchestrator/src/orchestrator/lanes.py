"""The Lanes Stage (spec 0002, stories 15-18, 25): one ticket at a time through implement → document
→ PR, each on its own branch and worktree from main, ending in a human merge.

Before any Lane starts, the Run's documents (spec, ADRs, ticket breakdown) go to main through a PR,
so every Lane branches from a main that already has them.
"""

from collections.abc import Callable
from typing import Any, Literal

from langgraph.types import interrupt

from orchestrator.agent import StepRequest
from orchestrator.approvals import gate_failed, record_gate, review_step
from orchestrator.commands import run, with_temporary_instance
from orchestrator.context import RunContext
from orchestrator.gitops import (
    changed_since_main,
    commit_all,
    lane_branch,
    push,
    run_branch,
    worktree,
)
from orchestrator.hashing import content_hash
from orchestrator.state import RunState

STAGE = "lanes"
PREFIX = {"Feature": "feat", "Fix": "fix", "Test": "test", "Docs": "docs"}

IMPLEMENT = """\
Implement ticket #{issue} test-first: write a failing test at the agreed seam, make it pass, repeat.
Only what the acceptance criteria need. Use the vocabulary in CONTEXT.md and respect the ADRs."""
DOCUMENT = """\
Update the documents ticket #{issue} touched: the spec's status, the integration-testing plan's
matrix, and the README where behaviour changed. Output docs_updated (paths), or none needed."""

PrOf = Callable[[RunState], int]


def lane(state: RunState) -> dict[str, Any]:
    return state["lanes"][state["lane_index"]]


def lane_tree(ctx: RunContext, state: RunState) -> Any:
    current = lane(state)
    return worktree(ctx.workspace, f"{ctx.run}-{current['issue']}", current["branch"])


class Begin:
    """Starts the Lanes Stage by sending the Run's documents to main through a PR."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        ctx.event("stage_started", STAGE)
        spec = state["spec"]
        body = (
            f"Spec, ADRs and the ticket breakdown for #{ctx.issue}, from Run {ctx.run}.\n\n"
            f"- Spec: `{spec['path']}`\n"
            + "".join(f"- ADR: `{a['path']}`\n" for a in state.get("adrs_accepted", []))
            + f"- Tickets: {', '.join(f'#{n}' for n in state['published'].values())}\n\n"
            "Each was approved at its Approval Checkpoint; merging this PR lets the Lanes start."
        )
        sha = push(ctx.workspace.worktrees / ctx.run, run_branch(ctx.run))
        pr = ctx.github.create_pr(
            run_branch(ctx.run),
            "main",
            f"docs: spec, ADRs and tickets for #{ctx.issue} ({ctx.run})",
            body,
        )
        ctx.event("pr_opened", STAGE, {"lane": "docs", "pr": pr, "sha": sha})
        ctx.mirror(f"Stage **lanes** started. First, the Run's documents go to main: PR #{pr}.")
        lanes = [
            {
                "key": t["key"],
                "issue": state["published"][t["key"]],
                "title": t["title"],
                "kind": t["kind"],
                "what": t["what"],
                "acceptance": t["acceptance"],
                "branch": lane_branch(state["published"][t["key"]], t["title"]),
                "pr": None,
                "sha": None,
            }
            for t in state["tickets"]
        ]
        return {"docs_pr": pr, "docs_sha": sha, "lanes": lanes, "lane_index": 0}


class AwaitChecks:
    """Waits until every required check on the PR's latest commit has finished."""

    def __init__(self, ctx: RunContext, pr_of: PrOf, sha_of: Callable[[RunState], str]) -> None:
        self.ctx, self.pr_of, self.sha_of = ctx, pr_of, sha_of

    def __call__(self, state: RunState) -> RunState:
        ctx, pr, sha = self.ctx, self.pr_of(state), self.sha_of(state)
        if ctx.github.pr(pr).state == "merged":  # branch protection only merges on green checks
            ctx.event("checks_passed", STAGE, {"pr": pr, "sha": sha, "via": "merged"})
            return {"ci": "passed"}
        checks = ctx.github.pr_checks(pr, sha)
        failed = [c for c in checks if c.state == "failure"]
        if failed:
            details = "\n".join(f"{c.name}: {c.details}" for c in failed)
            ctx.event(
                "checks_failed", STAGE, {"pr": pr, "sha": sha, "failed": [c.name for c in failed]}
            )
            return {"ci": "failed", "ci_output": details}
        if checks and all(c.state == "success" for c in checks):
            ctx.event("checks_passed", STAGE, {"pr": pr, "sha": sha})
            return {"ci": "passed"}
        interrupt({"kind": "checks", "pr": pr})
        return {"ci": "recheck"}


class RequestMerge:
    def __init__(self, ctx: RunContext, pr_of: PrOf) -> None:
        self.ctx, self.pr_of = ctx, pr_of

    def __call__(self, state: RunState) -> RunState:
        ctx, pr = self.ctx, self.pr_of(state)
        ctx.event("approval_requested", STAGE, {"checkpoint": f"merge:{pr}", "pr": pr})
        ctx.mirror(
            f"✅ Approval needed: **merge:{pr}**: PR #{pr} passed its required checks. Review and "
            "merge it on GitHub (the orchestrator never merges), "
            f"then run `orchestrate resume {ctx.run}`."
        )
        return {}


class AwaitMerge:
    """Merge approval means a human merged the PR on GitHub (spec 0002, story 25)."""

    def __init__(self, ctx: RunContext, pr_of: PrOf) -> None:
        self.ctx, self.pr_of = ctx, pr_of

    def __call__(self, state: RunState) -> RunState:
        ctx, pr_number = self.ctx, self.pr_of(state)
        pr = ctx.github.pr(pr_number)
        checkpoint = f"merge:{pr_number}"
        if pr.state == "merged":
            ctx.event(
                "approved",
                STAGE,
                {"checkpoint": checkpoint, "by": pr.merged_by, "channel": "github", "hash": None},
                actor="engineer",
            )
            ctx.mirror(f"PR #{pr_number} merged by @{pr.merged_by}.")
            return {"merge": "merged"}
        if pr.state == "closed":
            reason = f"PR #{pr_number} was closed without merging"
            ctx.event(
                "rejected",
                STAGE,
                {"checkpoint": checkpoint, "by": None, "channel": "github", "reason": reason},
            )
            ctx.event("stage_failed", STAGE, {"problems": [reason]})
            ctx.event("paused", STAGE, {"reason": reason})
            ctx.mirror(f"⏸️ Paused: {reason}.")
            return {"merge": "closed", "paused": True}
        interrupt({"kind": "merge", "pr": pr_number})
        return {"merge": "recheck"}


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
        self.ctx.mirror(f"⏸️ Paused: {reason}.")
        return {"paused": True}


class LaneBegin:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        current = lane(state)
        lane_tree(self.ctx, state)
        self.ctx.event(
            "stage_started", "implement", {"lane": current["key"], "issue": current["issue"]}
        )
        self.ctx.mirror(
            f"Lane **{current['key']}** started: #{current['issue']} {current['title']}."
        )
        return {"attempts": 0, "feedback": None}


class Implement:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx, current = self.ctx, lane(state)
        tree = lane_tree(ctx, state)
        ctx.call_agent(
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
                    "feedback": state.get("feedback"),
                },
                budget_usd=ctx.settings.cost_cap_step_usd,
            )
        )
        problems, _ = review_step(ctx, "implement", tree, dependencies_allowed=True)
        if problems:
            record_gate(ctx, "implement", "policy", problems)
            return gate_failed(ctx, "implement", "policy", state, problems)
        prefix = PREFIX.get(current["kind"], "feat")
        commit_all(tree, f"{prefix}: {current['title']} (#{current['issue']})")
        manifests = [
            m for m in changed_since_main(tree) if m in ctx.policies.raw["dependency_manifests"]
        ]
        if manifests:
            digest = manifests_hash(tree, manifests)
            if state.get("dependencies_approved", {}).get(current["key"]) != digest:
                push(tree, current["branch"])
                return {
                    "dependency_change": {"manifests": manifests, "hash": digest},
                    "feedback": None,
                }
        return {"dependency_change": None, "feedback": None}


def dependency_change(state: RunState) -> dict[str, Any]:
    change = state.get("dependency_change")
    assert change is not None
    return change


def manifests_hash(tree: Any, manifests: list[str]) -> str:
    return content_hash("\n".join((tree / m).read_text() for m in manifests))


def describe_dependency(state: RunState) -> tuple[str, str, str, str]:
    change = dependency_change(state)
    listed = ", ".join(f"`{m}`" for m in change["manifests"])
    return (
        f"dependency:{lane(state)['key']}",
        change["manifests"][0],
        change["hash"],
        f"\n\nLane {lane(state)['key']} changes dependency manifests: {listed}. "
        "Dependency changes always get a human review.",
    )


def dependency_location(state: RunState) -> tuple[str, str, list[str]]:
    current = lane(state)
    return (
        current["branch"],
        f"{state['run']}-{current['issue']}",
        dependency_change(state)["manifests"],
    )


class DependencyDecided:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        decision = state["decision"]
        if not decision["approved"]:
            return {
                "feedback": f"{decision['checkpoint']} rejected: {decision['reason']}",
                "attempts": 0,
            }
        approved = {
            **state.get("dependencies_approved", {}),
            lane(state)["key"]: dependency_change(state)["hash"],
        }
        return {"dependencies_approved": approved, "dependency_change": None}


class Verify:
    """The implement Exit Gate: the verify command, plus browser checks on a temporary instance."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx, current = self.ctx, lane(state)
        tree = lane_tree(ctx, state)
        s = ctx.settings
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
            return gate_failed(ctx, "implement", "verify", state, problems)
        ctx.event("stage_passed", "implement", {"lane": current["key"]})
        return {"feedback": None}


class Document:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx, current = self.ctx, lane(state)
        tree = lane_tree(ctx, state)
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
                    "feedback": state.get("feedback"),
                },
                budget_usd=ctx.settings.cost_cap_step_usd,
            )
        )
        problems, _ = review_step(ctx, "document", tree)
        if problems:
            record_gate(ctx, "document", "policy", problems)
            return gate_failed(ctx, "document", "policy", state, problems)
        commit_all(tree, f"docs: {current['title']} (#{current['issue']})")
        docs = list(result.output.get("docs_updated", []))
        ctx.event("stage_passed", "document", {"lane": current["key"], "docs_updated": docs})
        return {"feedback": None, "attempts": 0}


class OpenPr:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx, current = self.ctx, dict(lane(state))
        sha = push(lane_tree(ctx, state), current["branch"])
        if current["pr"] is None:
            prefix = PREFIX.get(current["kind"], "feat")
            spec_url = ctx.github.file_url("main", state["spec"]["path"])
            current["pr"] = ctx.github.create_pr(
                current["branch"],
                "main",
                f"{prefix}: {current['title']} (#{current['issue']})",
                f"Closes #{current['issue']}\n\nRun {ctx.run} (Issue #{ctx.issue}) · "
                f"[spec]({spec_url})\n\n## Acceptance criteria\n\n"
                + "\n".join(f"- [x] {a}" for a in current["acceptance"]),
            )
            ctx.event("pr_opened", "pr", {"lane": current["key"], "pr": current["pr"], "sha": sha})
            ctx.mirror(
                f"Lane **{current['key']}**: PR #{current['pr']} opened; waiting for its checks."
            )
        else:
            ctx.event("pr_updated", "pr", {"lane": current["key"], "pr": current["pr"], "sha": sha})
        current["sha"] = sha
        lanes = list(state["lanes"])
        lanes[state["lane_index"]] = current
        return {"lanes": lanes}


class CiFailed:
    """Failed required checks go back to implement with their output, within the retry limit."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        problems = [
            f"Required checks failed on PR #{lane(state)['pr']}:\n{state.get('ci_output', '')}"
        ]
        record_gate(self.ctx, "pr", "required checks pass", problems)
        return gate_failed(self.ctx, "pr", "required checks", state, problems)


class LaneMerged:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        current = lane(state)
        record_gate(self.ctx, "pr", "required checks pass", [])
        self.ctx.event("stage_passed", "pr", {"lane": current["key"], "pr": current["pr"]})
        index = state["lane_index"] + 1
        if index == len(state["lanes"]):
            self.ctx.event(
                "stage_passed", STAGE, {"artifacts": {"prs": [ln["pr"] for ln in state["lanes"]]}}
            )
        return {"lane_index": index}


def docs_pr(state: RunState) -> int:
    return state["docs_pr"]


def docs_sha(state: RunState) -> str:
    return state["docs_sha"]


def lane_pr(state: RunState) -> int:
    pr = lane(state)["pr"]
    assert pr is not None
    return int(pr)


def lane_sha(state: RunState) -> str:
    return str(lane(state)["sha"])


def after_docs_checks(state: RunState) -> Literal["recheck", "request_merge", "stop"]:
    if state["ci"] == "recheck":
        return "recheck"
    return "request_merge" if state["ci"] == "passed" else "stop"


def after_merge(state: RunState) -> Literal["recheck", "next", "stop"]:
    if state["merge"] == "recheck":
        return "recheck"
    return "next" if state["merge"] == "merged" else "stop"


def after_implement(state: RunState) -> Literal["verify", "dependency", "implement", "stop"]:
    if state.get("paused"):
        return "stop"
    if state.get("feedback"):
        return "implement"
    return "dependency" if state.get("dependency_change") else "verify"


def after_dependency(state: RunState) -> Literal["verify", "implement"]:
    return "verify" if state["decision"]["approved"] else "implement"


def after_verify(state: RunState) -> Literal["document", "implement", "stop"]:
    if state.get("paused"):
        return "stop"
    return "implement" if state.get("feedback") else "document"


def after_document(state: RunState) -> Literal["open_pr", "document", "stop"]:
    if state.get("paused"):
        return "stop"
    return "document" if state.get("feedback") else "open_pr"


def after_lane_checks(state: RunState) -> Literal["recheck", "request_merge", "failed"]:
    if state["ci"] == "recheck":
        return "recheck"
    return "request_merge" if state["ci"] == "passed" else "failed"


def after_ci_failed(state: RunState) -> Literal["implement", "stop"]:
    return "stop" if state.get("paused") else "implement"


def after_lane(state: RunState) -> Literal["next_lane", "readiness"]:
    return "next_lane" if state["lane_index"] < len(state["lanes"]) else "readiness"
