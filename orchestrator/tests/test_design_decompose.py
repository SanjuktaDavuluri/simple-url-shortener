"""Design and decompose Stages: ADR and ticket Approval Checkpoints, tickets published (#28)."""

import json
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from test_requirements_stage import SPEC_PATH, spec_text, writes_spec

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.agent import StepRequest, StepResult

ADR_1 = "docs/adr/0020-expiry-as-a-timestamp.md"
ADR_2 = "docs/adr/0021-expired-links-return-410.md"


def adr_text(title: str = "Expiry is a timestamp", status: str = "proposed", omit: str = "") -> str:
    parts = {
        "frontmatter": f"---\nstatus: {status}\ndate: 2026-10-08\n---\n",
        "title": f"# {title}\n\nContext for the decision.\n",
        "decision": "## Decision\n\nStore an expiry timestamp.\n",
        "options": (
            "## Options considered and the trade-offs\n\n"
            "| | Option | What we'd gain | What it would cost | Verdict |\n"
            "|---|---|---|---|---|\n"
            "| A | **Timestamp** | Simple | No click limits | **Chosen** |\n"
            "| B | Click budget | Flexible | Complex | Rejected |\n"
        ),
        "consequences": "## Consequences\n\n- A clock is needed in tests.\n",
    }
    return "\n".join(text for key, text in parts.items() if key != omit)


def writes_adrs(
    files: dict[str, str], no_adr_reason: str | None = None
) -> Callable[[StepRequest], StepResult]:
    def write(request: StepRequest) -> StepResult:
        for path, text in files.items():
            target = request.workspace / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
        output: dict[str, Any] = {"adrs": list(files)}
        if no_adr_reason is not None:
            output["no_adr_reason"] = no_adr_reason
        return StepResult(output=output, files_changed=tuple(files), cost_usd=0.3)

    return write


def ticket(key: str, title: str, blocked_by: tuple[str, ...] = (), **extra: Any) -> dict[str, Any]:
    return {
        "key": key,
        "title": title,
        "what": f"{title}, end to end.",
        "acceptance": [f"{title} works through the HTTP seam"],
        "blocked_by": list(blocked_by),
        "kind": "Feature",
        **extra,
    }


TICKETS = [
    ticket("T1", "Store expiry on a Link"),
    ticket("T2", "Expired Links return 410", ("T1",)),
    ticket("T3", "Show expiry on the web page", ("T1",)),
]


def breakdown(tickets: list[dict[str, Any]]) -> StepResult:
    return StepResult(output={"tickets": tickets}, cost_usd=0.2)


def events(repo: Path) -> list[dict[str, Any]]:
    path = repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()]


def of_type(repo: Path, type: str) -> list[dict[str, Any]]:
    return [e for e in events(repo) if e["type"] == type]


def remote_file(repo: Path, path: str) -> str:
    remote = repo.parent / "remote.git"
    return subprocess.run(
        ["git", "--git-dir", str(remote), "show", f"docs/run-R-0001:{path}"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def stage_state(out: str, stage: str) -> str:
    return next(line.split()[1] for line in out.splitlines() if line.startswith(f"  {stage} "))


@pytest.fixture
def approved_spec(
    orchestrate: Orchestrate, github: InMemoryGitHub, agent: ScriptedAgent
) -> Callable[[], None]:
    """Takes Issue #42 through requirements; the design script must be set before calling."""
    github.add_issue(42, "Expiring links", "Links should be able to expire. Roadmap R18.")

    def run() -> None:
        agent.script["requirements"] = [writes_spec(spec_text())]
        orchestrate("start", "42")
        code, out = orchestrate("approve", "R-0001", "spec")
        assert code == 0, out

    return run


# Design


def test_no_adr_is_a_valid_outcome_recorded_with_its_reason(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    approved_spec: Callable[[], None],
) -> None:
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Follows ADR 0002; nothing new.")]

    approved_spec()

    [passed] = [e for e in of_type(repo, "stage_passed") if e["stage"] == "design"]
    assert passed["data"]["artifacts"] == {"adrs": []}
    assert passed["data"]["no_adr_reason"] == "Follows ADR 0002; nothing new."
    assert any("No ADR" in c.body and "nothing new" in c.body for c in github.comments(42))
    assert agent.requests[-1].stage == "decompose"


def test_no_adr_needs_a_reason(
    orchestrate: Orchestrate, agent: ScriptedAgent, approved_spec: Callable[[], None]
) -> None:
    agent.script["design"] = [writes_adrs({}), writes_adrs({}, no_adr_reason="Nothing new.")]

    approved_spec()

    design = [r for r in agent.requests if r.stage == "design"]
    assert "reason" in design[1].context["feedback"].lower()


def test_the_design_agent_gets_the_approved_spec(
    orchestrate: Orchestrate, agent: ScriptedAgent, repo: Path, approved_spec: Callable[[], None]
) -> None:
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]

    approved_spec()

    design = next(r for r in agent.requests if r.stage == "design")
    spec = next(e for e in of_type(repo, "stage_passed") if e["stage"] == "requirements")
    assert design.context["spec"] == spec["data"]["artifacts"]["spec"]


def test_a_proposed_adr_is_pushed_and_waits_for_its_own_approval(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    approved_spec: Callable[[], None],
) -> None:
    agent.script["design"] = [writes_adrs({ADR_1: adr_text()})]

    approved_spec()

    assert remote_file(repo, ADR_1) == adr_text()
    [request] = [
        c for c in github.comments(42) if "Approval needed" in c.body and "adr-0020" in c.body
    ]
    assert f"https://github.test/blob/docs/run-R-0001/{ADR_1}" in request.body
    assert "approved:adr-0020" in request.body
    _, out = orchestrate("status", "R-0001")
    assert "Approvals waiting: adr-0020" in out
    assert stage_state(out, "design") == "running"


def test_each_adr_is_approved_separately_and_becomes_accepted(
    orchestrate: Orchestrate, agent: ScriptedAgent, repo: Path, approved_spec: Callable[[], None]
) -> None:
    agent.script["design"] = [writes_adrs({ADR_1: adr_text(), ADR_2: adr_text("410 Gone")})]
    approved_spec()

    orchestrate("approve", "R-0001", "adr-0020")

    assert "status: accepted" in remote_file(repo, ADR_1)
    assert "status: proposed" in remote_file(repo, ADR_2)
    _, out = orchestrate("status", "R-0001")
    assert "Approvals waiting: adr-0021" in out

    orchestrate("approve", "R-0001", "adr-0021")

    assert "status: accepted" in remote_file(repo, ADR_2)
    approved = [e["data"]["checkpoint"] for e in of_type(repo, "approved")]
    assert approved == ["spec", "adr-0020", "adr-0021"]
    _, out = orchestrate("status", "R-0001")
    assert stage_state(out, "design") == "passed"


def test_an_adr_can_be_approved_by_a_maintainers_label(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    approved_spec: Callable[[], None],
) -> None:
    agent.script["design"] = [writes_adrs({ADR_1: adr_text()})]
    approved_spec()
    github.add_label(42, "approved:adr-0020", by="maintainer")

    orchestrate("resume", "R-0001")

    assert of_type(repo, "approved")[-1]["data"]["channel"] == "label"
    assert "status: accepted" in remote_file(repo, ADR_1)


@pytest.mark.parametrize(
    ("adr", "path", "complaint"),
    [
        (adr_text(omit="options"), ADR_1, "Options considered"),
        (adr_text(omit="consequences"), ADR_1, "Consequences"),
        (adr_text().replace("**Chosen**", "Maybe"), ADR_1, "Chosen"),
        (
            adr_text().replace("| B | Click budget | Flexible | Complex | Rejected |\n", ""),
            ADR_1,
            "Rejected",
        ),
        (adr_text(status="accepted"), ADR_1, "status: proposed"),
        (adr_text(), "docs/decisions/expiry.md", "docs/adr/NNNN-"),
    ],
)
def test_the_adr_gate_names_what_is_missing(
    orchestrate: Orchestrate,
    agent: ScriptedAgent,
    approved_spec: Callable[[], None],
    adr: str,
    path: str,
    complaint: str,
) -> None:
    agent.script["design"] = [writes_adrs({path: adr}), writes_adrs({ADR_1: adr_text()})]

    approved_spec()

    design = [r for r in agent.requests if r.stage == "design"]
    assert complaint in design[1].context["feedback"]


def test_an_adr_number_already_on_main_is_refused(
    orchestrate: Orchestrate, agent: ScriptedAgent, repo: Path, approved_spec: Callable[[], None]
) -> None:
    (repo / "docs" / "adr").mkdir()
    (repo / "docs" / "adr" / "0020-old.md").write_text(adr_text(status="accepted"))
    subprocess.run(["git", "add", "."], cwd=repo, check=True)
    subprocess.run(["git", "commit", "-qm", "old ADR"], cwd=repo, check=True)
    subprocess.run(["git", "push", "-q"], cwd=repo, check=True)
    agent.script["design"] = [
        writes_adrs({ADR_1: adr_text()}),
        writes_adrs({"docs/adr/0021-new.md": adr_text()}),
    ]

    approved_spec()

    design = [r for r in agent.requests if r.stage == "design"]
    assert "0020" in design[1].context["feedback"]


def test_rejecting_an_adr_sends_the_reason_back_and_the_revision_needs_approval(
    orchestrate: Orchestrate, agent: ScriptedAgent, repo: Path, approved_spec: Callable[[], None]
) -> None:
    revised = adr_text("Expiry is an instant in UTC")
    agent.script["design"] = [writes_adrs({ADR_1: adr_text()}), writes_adrs({ADR_1: revised})]
    approved_spec()

    orchestrate("reject", "R-0001", "adr-0020", "--reason", "Say which time zone.")

    feedback = [r for r in agent.requests if r.stage == "design"][1].context["feedback"]
    assert "adr-0020" in feedback and "Say which time zone." in feedback
    assert remote_file(repo, ADR_1) == revised
    _, out = orchestrate("status", "R-0001")
    assert "Approvals waiting: adr-0020" in out


def test_an_accepted_adr_cannot_be_changed_by_a_revision(
    orchestrate: Orchestrate, agent: ScriptedAgent, repo: Path, approved_spec: Callable[[], None]
) -> None:
    agent.script["design"] = [
        writes_adrs({ADR_1: adr_text(), ADR_2: adr_text("410 Gone")}),
        writes_adrs({ADR_1: adr_text("Rewritten"), ADR_2: adr_text("410 Gone, revised")}),
        writes_adrs({ADR_2: adr_text("410 Gone, revised")}),
    ]
    approved_spec()
    orchestrate("approve", "R-0001", "adr-0020")

    orchestrate("reject", "R-0001", "adr-0021", "--reason", "Explain 410 vs 404.")

    design = [r for r in agent.requests if r.stage == "design"]
    assert "accepted" in design[2].context["feedback"] and ADR_1 in design[2].context["feedback"]
    assert design[2].context["accepted_adrs"] == [ADR_1]
    assert "status: accepted" in remote_file(repo, ADR_1)


# Decompose


def no_adr_then(orchestrate: Orchestrate, agent: ScriptedAgent, *results: Any) -> None:
    agent.script["design"] = [writes_adrs({}, no_adr_reason="Nothing new.")]
    agent.script["decompose"] = list(results)


def test_the_ticket_breakdown_waits_for_approval(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    approved_spec: Callable[[], None],
) -> None:
    no_adr_then(orchestrate, agent, breakdown(TICKETS))

    approved_spec()

    plan = json.loads(remote_file(repo, "delivery/runs/R-0001/tickets.json"))
    assert [t["key"] for t in plan] == ["T1", "T2", "T3"]
    [request] = [
        c for c in github.comments(42) if "Approval needed" in c.body and "tickets" in c.body
    ]
    assert "Expired Links return 410" in request.body and "T1" in request.body
    _, out = orchestrate("status", "R-0001")
    assert "Approvals waiting: tickets" in out
    assert github.created == []


@pytest.mark.parametrize(
    ("tickets", "complaint"),
    [
        ([], "at least one ticket"),
        ([ticket("T1", "One", ("T9",))], "t9"),
        ([ticket("T1", "One", ("T2",)), ticket("T2", "Two", ("T1",))], "cycle"),
        ([ticket("T1", "One", acceptance=[])], "acceptance"),
        ([ticket("T1", "One"), ticket("T1", "Again")], "duplicate"),
        ([ticket("T1", "One", kind="Epic")], "kind"),
    ],
)
def test_the_breakdown_gate_names_what_is_wrong(
    orchestrate: Orchestrate,
    agent: ScriptedAgent,
    approved_spec: Callable[[], None],
    tickets: list[dict[str, Any]],
    complaint: str,
) -> None:
    no_adr_then(orchestrate, agent, breakdown(tickets), breakdown(TICKETS))

    approved_spec()

    decompose = [r for r in agent.requests if r.stage == "decompose"]
    assert complaint in decompose[1].context["feedback"].lower()


def test_approved_tickets_are_published_in_dependency_order(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    approved_spec: Callable[[], None],
) -> None:
    no_adr_then(orchestrate, agent, breakdown(list(reversed(TICKETS))))
    approved_spec()

    code, out = orchestrate("approve", "R-0001", "tickets")

    assert code == 0, out
    t1, t2, t3 = github.created
    assert [github.issues[n].title for n in (t1, t2, t3)] == [
        "Store expiry on a Link",
        "Expired Links return 410",
        "Show expiry on the web page",
    ]
    for n in (t1, t2, t3):
        assert github.issues[n].labels == ("ready-for-agent",)
        assert github.issue_meta[n]["milestone"] == "Release 2: Orchestrated delivery"
        assert github.board[n] == {"Status": "Todo", "Release": "2", "Kind": "Feature"}
        assert f"https://github.test/blob/docs/run-R-0001/{SPEC_PATH}" in github.issues[n].body
    assert github.blocked_by == {t2: [t1], t3: [t1]}
    assert f"- #{t1}" in github.issues[t2].body
    assert "None (can start immediately)" in github.issues[t1].body
    assert "- [ ] Expired Links return 410 works through the HTTP seam" in github.issues[t2].body
    [published] = of_type(repo, "tickets_published")
    assert published["data"]["issues"] == {"T1": t1, "T2": t2, "T3": t3}
    passed = next(e for e in of_type(repo, "stage_passed") if e["stage"] == "decompose")
    assert [t["issue"] for t in passed["data"]["artifacts"]["tickets"]] == [t1, t2, t3]
    _, out = orchestrate("status", "R-0001")
    assert stage_state(out, "decompose") == "passed"


def test_rejecting_the_breakdown_revises_it_before_anything_is_published(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    approved_spec: Callable[[], None],
) -> None:
    no_adr_then(orchestrate, agent, breakdown(TICKETS), breakdown(TICKETS[:2]))
    approved_spec()

    orchestrate("reject", "R-0001", "tickets", "--reason", "Drop the web page ticket.")

    decompose = [r for r in agent.requests if r.stage == "decompose"]
    assert decompose[1].context["feedback"] == "Drop the web page ticket."
    assert github.created == []
    orchestrate("approve", "R-0001", "tickets")
    assert len(github.created) == 2
