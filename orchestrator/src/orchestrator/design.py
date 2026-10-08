"""The design Stage (spec 0002, stories 11-12): ADRs only for significant decisions, or none.

Each proposed ADR has its own Approval Checkpoint (`adr-NNNN`) and becomes `accepted` on approval.
An accepted ADR never changes; a later decision supersedes it with a new ADR.
"""

import re
from pathlib import Path
from typing import Literal

from orchestrator import lineage
from orchestrator.agent import StepRequest
from orchestrator.approvals import gate_failed, record_gate, review_step
from orchestrator.context import RunContext
from orchestrator.gitops import commit_and_push, ensure_run_worktree, files_on_main
from orchestrator.hashing import content_hash
from orchestrator.state import RunState

STAGE = "design"
ADR_PATH = re.compile(r"^docs/adr/(\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")

INSTRUCTIONS = """\
Read the approved spec. Propose an ADR only for a decision that is hard to reverse, surprising
without context, and the result of a real trade-off. Write each as docs/adr/NNNN-<slug>.md with
`status: proposed`, an options table (option, what we'd gain, what it would cost, verdict) with
exactly one **Chosen** and the rejected alternatives, and Consequences. Output: adrs (paths). If
no decision qualifies, output adrs: [] and no_adr_reason."""


def checkpoint(path: str) -> str:
    match = ADR_PATH.match(path)
    return f"adr-{match.group(1)}" if match else f"adr-{Path(path).stem}"


def adr_problems(path: str, text: str) -> list[str]:
    problems = []
    if not re.match(r"\A---\n(?:.*\n)*?status:\s*proposed\s*\n(?:.*\n)*?---\n", text):
        problems.append(f"{path}: the frontmatter must say status: proposed")
    if not re.search(r"^# \S", text, re.MULTILINE):
        problems.append(f"{path}: missing the title (# …)")
    options = re.search(r"^## Options considered.*$", text, re.MULTILINE)
    if not options:
        problems.append(f"{path}: missing the section ## Options considered and the trade-offs")
    else:
        table = text[options.end() :]
        header = next((line for line in table.splitlines() if line.startswith("|")), "").lower()
        if "gain" not in header or "cost" not in header:
            problems.append(f"{path}: the options table needs what each option gains and costs")
        if table.count("**Chosen**") != 1:
            problems.append(f"{path}: exactly one option must be marked **Chosen**")
        if "Rejected" not in table:
            problems.append(f"{path}: list the Rejected alternatives, not only the chosen option")
    if "\n## Consequences\n" not in text:
        problems.append(f"{path}: missing the section ## Consequences")
    return problems


class Begin:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        self.ctx.event("stage_started", STAGE)
        self.ctx.mirror("Stage **design** started: ADRs for significant decisions, if any.")
        # accepted ADRs never change, so a re-planned design keeps them and adds to them
        return {"feedback": None, "attempts": 0, "accepted_adrs": state.get("accepted_adrs", {})}


class Draft:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        result = ctx.call_agent(
            StepRequest(
                run=ctx.run,
                stage=STAGE,
                instructions=INSTRUCTIONS,
                workspace=ensure_run_worktree(ctx.workspace, ctx.run),
                context={
                    "spec": state["spec"],
                    "accepted_adrs": sorted(state.get("accepted_adrs", {})),
                    "feedback": state.get("feedback"),
                },
                budget_usd=ctx.settings.cost_cap_step_usd,
            )
        )
        reason = result.output.get("no_adr_reason")
        return {
            "adrs": [str(p) for p in result.output.get("adrs", [])],
            "no_adr_reason": str(reason).strip() if reason else None,
        }


class Gate:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx = self.ctx
        worktree = ensure_run_worktree(ctx.workspace, ctx.run)
        accepted = state.get("accepted_adrs", {})
        proposed = [p for p in state.get("adrs", []) if p not in accepted]
        problems: list[str] = []
        if not proposed and not accepted and not state.get("no_adr_reason"):
            problems.append("No ADR was proposed: give the reason no ADR is needed (no_adr_reason)")
        on_main = {
            m.group(1)
            for name in files_on_main(ctx.workspace, "docs/adr")
            if (m := ADR_PATH.match(name))
        }
        for path in proposed:
            match = ADR_PATH.match(path)
            file = worktree / path
            if not match:
                problems.append(f"ADRs must be new files named docs/adr/NNNN-<slug>.md, not {path}")
            elif match.group(1) in on_main:
                problems.append(
                    f"{path}: ADR {match.group(1)} already exists on main; use a new number"
                )
            elif not file.is_file():
                problems.append(f"{path} was not written")
            else:
                problems.extend(adr_problems(path, file.read_text()))
        for path, accepted_hash in accepted.items():
            file = worktree / path
            if not file.is_file() or content_hash(file.read_text()) != accepted_hash:
                problems.append(
                    f"{path} is accepted and can't change; restore it, and propose a new ADR "
                    "that supersedes it if the decision must change"
                )
        problems += review_step(ctx, STAGE, worktree)[0]
        record_gate(ctx, STAGE, "ADRs are complete", problems)
        if problems:
            return gate_failed(ctx, STAGE, "ADR", state, problems)
        if proposed:
            commit_and_push(
                worktree, ctx.run, proposed, f"docs: propose ADRs for #{ctx.issue} ({ctx.run})"
            )
        return {"feedback": None, "pending_adrs": sorted(proposed)}


class DescribeAdr:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> tuple[str, str, str, str]:
        path = state["pending_adrs"][0]
        text = (ensure_run_worktree(self.ctx.workspace, self.ctx.run) / path).read_text()
        return checkpoint(path), path, content_hash(text), ""


class Decided:
    """Approved: mark the ADR accepted on the Run's branch. Rejected: revise with the reason."""

    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        ctx, decision = self.ctx, state["decision"]
        if not decision["approved"]:
            return {
                "feedback": f"{decision['checkpoint']} rejected: {decision['reason']}",
                "attempts": 0,
            }
        path, *rest = state["pending_adrs"]
        worktree = ensure_run_worktree(ctx.workspace, ctx.run)
        file = worktree / path
        accepted_text = re.sub(
            r"^status:\s*proposed\s*$",
            "status: accepted",
            file.read_text(),
            count=1,
            flags=re.MULTILINE,
        )
        file.write_text(accepted_text)
        number = checkpoint(path).removeprefix("adr-")
        commit_and_push(
            worktree, ctx.run, [path], f"docs: accept ADR {number} (#{ctx.issue}, {ctx.run})"
        )
        accepted = {**state.get("accepted_adrs", {}), path: content_hash(accepted_text)}
        return {"accepted_adrs": accepted, "pending_adrs": rest}


class Done:
    def __init__(self, ctx: RunContext) -> None:
        self.ctx = ctx

    def __call__(self, state: RunState) -> RunState:
        accepted = state.get("accepted_adrs", {})
        adrs = [{"path": p, "hash": h} for p, h in sorted(accepted.items())]
        data: dict[str, object] = {"artifacts": {"adrs": adrs}}
        if not adrs:
            data["no_adr_reason"] = state.get("no_adr_reason")
            self.ctx.mirror(f"No ADR needed: {state.get('no_adr_reason')}")
        else:
            self.ctx.mirror(f"Design passed: {len(adrs)} ADR(s) accepted.")
        updated = lineage.passed(self.ctx, {**state, "adrs_accepted": adrs}, STAGE, data)
        return {"adrs_accepted": adrs, "feedback": None, "attempts": 0, "lineage": updated}


def after_gate(state: RunState) -> Literal["request_approval", "draft", "done", "stop"]:
    if state.get("paused"):
        return "stop"
    if state.get("feedback"):
        return "draft"
    return "request_approval" if state.get("pending_adrs") else "done"


def after_decided(state: RunState) -> Literal["request_approval", "draft", "done"]:
    if not state["decision"]["approved"]:
        return "draft"
    return "request_approval" if state.get("pending_adrs") else "done"
