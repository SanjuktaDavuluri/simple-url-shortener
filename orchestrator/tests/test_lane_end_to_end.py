"""One ticket through a Lane, then release readiness and close-out (#29), through the CLI seam."""

import json
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

from test_design_decompose import ticket
from test_requirements_stage import SPEC_PATH, spec_text

from conftest import Orchestrate
from fakes import InMemoryGitHub, ScriptedAgent
from orchestrator.agent import StepRequest, StepResult
from orchestrator.github import Check

TICKET = ticket("T1", "Store expiry on a Link")


def git(cwd: Path, *args: str, check: bool = True) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=check
    ).stdout


def writes(files: dict[str, str], **output: Any) -> Callable[[StepRequest], StepResult]:
    def write(request: StepRequest) -> StepResult:
        for path, text in files.items():
            target = request.workspace / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
        return StepResult(output=output, files_changed=tuple(files), cost_usd=0.5)

    return write


def merge(repo: Path, github: InMemoryGitHub, pr: int, by: str = "maintainer") -> None:
    """What a human merging on GitHub does: the PR's branch lands on main."""
    head = github.pr(pr).head
    merger = repo.parent / "merger"
    if not merger.exists():
        git(repo.parent, "clone", "-q", str(repo.parent / "remote.git"), str(merger))
        git(merger, "config", "user.email", "human@example.com")
        git(merger, "config", "user.name", "Human")
    git(merger, "fetch", "-q", "origin")
    git(merger, "checkout", "-q", "main")
    git(merger, "reset", "-q", "--hard", "origin/main")
    commits = git(merger, "log", "--format=%s", f"main..origin/{head}").split("\n")
    git(merger, "merge", "-q", "--no-ff", f"origin/{head}", "-m", f"Merge pull request #{pr}")
    git(merger, "push", "-q", "origin", "main")
    github.record_merge(pr, by, [c for c in commits if c])


def events(repo: Path) -> list[dict[str, Any]]:
    path = repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()]


def of_type(repo: Path, type: str) -> list[dict[str, Any]]:
    return [e for e in events(repo) if e["type"] == type]


def on_remote(repo: Path, ref: str, path: str) -> str:
    return git(repo, "--git-dir", str(repo.parent / "remote.git"), "show", f"{ref}:{path}")


def stage_state(out: str, stage: str) -> str:
    return next(line.split()[1] for line in out.splitlines() if line.startswith(f"  {stage} "))


def lane_script(agent: ScriptedAgent, *implement: Any) -> None:
    agent.script["implement"] = list(implement) or [writes({"feature.txt": "expiry\n"})]
    agent.script["document"] = [
        writes({"docs/notes.md": "Expiry.\n"}, docs_updated=["docs/notes.md"])
    ]


def through_docs_pr(
    orchestrate: Orchestrate, github: InMemoryGitHub, repo: Path, published: Callable[[], int]
) -> int:
    docs_pr = published()
    merge(repo, github, docs_pr)
    orchestrate("resume", "R-0001")
    return github.pr_for_head("feat/100-store-expiry-on-a-link")


# The Run's documents reach main before any Lane starts


def test_the_runs_documents_go_to_main_through_a_pr_before_any_lane(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)

    docs_pr = published()

    pr = github.pr(docs_pr)
    assert (pr.head, pr.base) == ("docs/run-R-0001", "main")
    assert "R-0001" in pr.body and "#42" in pr.body
    _, out = orchestrate("status", "R-0001")
    assert f"Approvals waiting: merge:{docs_pr}" in out
    assert stage_state(out, "lanes") == "running"
    assert not [r for r in agent.requests if r.stage == "implement"]


def test_the_orchestrator_never_merges(
    orchestrate: Orchestrate, agent: ScriptedAgent, published: Callable[[], int]
) -> None:
    lane_script(agent)
    docs_pr = published()

    code, out = orchestrate("approve", "R-0001", f"merge:{docs_pr}")

    assert code != 0
    assert "merge" in out.lower() and "GitHub" in out


def test_resume_keeps_waiting_until_the_docs_pr_is_merged(
    orchestrate: Orchestrate, agent: ScriptedAgent, published: Callable[[], int]
) -> None:
    lane_script(agent)
    docs_pr = published()

    _, out = orchestrate("resume", "R-0001")

    assert f"Approvals waiting: merge:{docs_pr}" in out
    assert not [r for r in agent.requests if r.stage == "implement"]


# implement


def test_the_lane_implements_on_its_own_branch_from_main_and_passes_verify(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)

    pr = through_docs_pr(orchestrate, github, repo, published)

    implement = next(r for r in agent.requests if r.stage == "implement")
    assert implement.workspace == repo / ".orchestrator" / "worktrees" / "R-0001-100"
    assert implement.context["ticket"]["issue"] == 100
    assert (implement.workspace / SPEC_PATH).read_text() == spec_text()  # branched from main
    assert on_remote(repo, "feat/100-store-expiry-on-a-link", "feature.txt") == "expiry\n"
    subjects = git(
        repo,
        "--git-dir",
        str(repo.parent / "remote.git"),
        "log",
        "--format=%s",
        "main..feat/100-store-expiry-on-a-link",
    ).splitlines()
    assert subjects and all("(#100)" in s for s in subjects)
    gate = [e for e in of_type(repo, "gate_result") if e["stage"] == "implement"]
    assert gate[-1]["data"]["passed"] is True
    assert pr in github.prs
    assert git(repo, "branch", "--show-current").strip() == "main"


def test_a_failing_verify_goes_back_to_the_agent_with_its_output(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent, writes({"other.txt": "x"}), writes({"feature.txt": "expiry\n"}))

    through_docs_pr(orchestrate, github, repo, published)

    implement = [r for r in agent.requests if r.stage == "implement"]
    assert len(implement) == 2
    assert "test -f feature.txt" in implement[1].context["feedback"]


def test_a_web_change_runs_browser_checks_against_a_temporary_instance(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    probe: Path,
    published: Callable[[], int],
) -> None:
    page = {"feature.txt": "x", "src/main/resources/templates/index.html": "<p>expiry</p>"}
    lane_script(agent, writes(page))

    through_docs_pr(orchestrate, github, repo, published)

    start, check, stop = probe.read_text().splitlines()
    _, port, data_dir, cwd = start.split()
    assert port != "8000" and int(port) > 1024
    assert Path(data_dir).name.startswith("orchestrator-") and not Path(data_dir).exists()
    assert cwd.endswith("R-0001-100")
    assert check == f"check http://localhost:{port}"
    assert stop == f"stop {port} {data_dir}"


def test_no_web_change_means_no_browser_checks(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    probe: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)

    through_docs_pr(orchestrate, github, repo, published)

    assert not probe.exists()


# document and PR


def test_the_document_step_writes_a_per_ticket_note_and_leaves_the_readme_alone(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)

    through_docs_pr(orchestrate, github, repo, published)

    document = next(r for r in agent.requests if r.stage == "document")
    instructions = " ".join(document.instructions.split())
    assert "docs/Issue-100-readme.md" in instructions
    assert "Never edit README.md" in instructions
    assert "without reading the whole plan" in instructions


def test_the_document_stage_commits_doc_updates_and_the_pr_closes_the_ticket(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)

    pr = github.pr(through_docs_pr(orchestrate, github, repo, published))

    assert on_remote(repo, pr.head, "docs/notes.md") == "Expiry.\n"
    assert pr.base == "main"
    assert pr.title.startswith("feat: Store expiry on a Link")
    assert "Closes #100" in pr.body and "R-0001" in pr.body
    document = next(r for r in agent.requests if r.stage == "document")
    assert document.context["changed_files"] == ["feature.txt"]


def test_pending_ci_keeps_the_lane_waiting(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)
    github.default_checks = (Check("Verify", "pending"),)

    pr = through_docs_pr(orchestrate, github, repo, published)
    _, out = orchestrate("resume", "R-0001")

    assert f"Waiting for: required checks on PR #{pr}" in out
    assert f"merge:{pr}" not in out


def test_failing_ci_goes_back_to_implement_with_the_check_output(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent, writes({"feature.txt": "v1\n"}), writes({"feature.txt": "v2\n"}))
    agent.script["document"].append(writes({}, docs_updated=[]))
    github.default_checks = (Check("Verify", "pending"),)
    pr = through_docs_pr(orchestrate, github, repo, published)
    head = git(repo, "--git-dir", str(repo.parent / "remote.git"), "rev-parse", github.pr(pr).head)
    github.set_checks(pr, head.strip(), Check("Verify", "failure", "LinkApiIT failed"))

    orchestrate("resume", "R-0001")

    implement = [r for r in agent.requests if r.stage == "implement"]
    assert "LinkApiIT failed" in implement[1].context["feedback"]
    assert on_remote(repo, github.pr(pr).head, "feature.txt") == "v2\n"


def test_green_ci_waits_for_a_human_merge(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)

    pr = through_docs_pr(orchestrate, github, repo, published)

    _, out = orchestrate("status", "R-0001")
    assert f"Approvals waiting: merge:{pr}" in out
    assert any(f"PR #{pr}" in c.body and "merge" in c.body.lower() for c in github.comments(42))


# release readiness and close-out


def test_after_the_merge_the_run_closes_out_with_its_report_and_event_log(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)
    pr = through_docs_pr(orchestrate, github, repo, published)
    merge(repo, github, pr)

    code, out = orchestrate("resume", "R-0001")

    assert code == 0, out
    close_out = github.pr(github.pr_for_head("docs/run-R-0001-close-out"))
    assert close_out.base == "main" and "R-0001" in close_out.title
    assert "Closes #42" in close_out.body  # merging it closes the Run's Issue (#79)
    opened = [e["data"] for e in of_type(repo, "pr_opened")]
    assert {"lane": "close_out", "pr": close_out.number, "sha": opened[-1]["sha"]} == opened[-1]
    branch = close_out.head
    committed = on_remote(repo, branch, "delivery/runs/R-0001/events.jsonl").splitlines()
    assert json.loads(committed[-1])["type"] == "run_finished"
    assert "status: implemented" in on_remote(repo, branch, SPEC_PATH)
    report = on_remote(repo, branch, "delivery/runs/R-0001/report.md")
    for expected in (
        "R-0001",
        "#42",
        "spec",
        "tickets",
        f"merge:{pr}",
        "maintainer",
        f"#{pr}",
        "$",
    ):
        assert expected in report
    assert "State: finished" in out
    for stage in ("lanes", "release_readiness", "close_out"):
        assert stage_state(out, stage) == "passed"
    readiness = next(e for e in of_type(repo, "gate_result") if e["stage"] == "release_readiness")
    assert readiness["data"]["passed"] is True


def test_the_committed_event_log_verifies(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)
    pr = through_docs_pr(orchestrate, github, repo, published)
    merge(repo, github, pr)
    orchestrate("resume", "R-0001")
    committed = on_remote(repo, "docs/run-R-0001-close-out", "delivery/runs/R-0001/events.jsonl")
    local = repo / ".orchestrator" / "runs" / "R-0001" / "events.jsonl"
    local.unlink()
    (repo / "delivery" / "runs" / "R-0001").mkdir(parents=True)
    (repo / "delivery" / "runs" / "R-0001" / "events.jsonl").write_text(committed)

    code, out = orchestrate("verify", "R-0001")

    assert code == 0 and "intact" in out


def test_release_readiness_fails_on_a_commit_without_its_ticket(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)
    pr = through_docs_pr(orchestrate, github, repo, published)
    head = github.pr(pr).head
    pusher = repo.parent / "pusher"
    git(repo.parent, "clone", "-q", "-b", head, str(repo.parent / "remote.git"), str(pusher))
    (pusher / "extra.txt").write_text("x")
    git(pusher, "add", ".")
    git(pusher, "-c", "user.email=h@example.com", "-c", "user.name=H", "commit", "-qm", "tweak")
    git(pusher, "push", "-q")
    merge(repo, github, pr)

    orchestrate("resume", "R-0001")

    readiness = next(e for e in of_type(repo, "gate_result") if e["stage"] == "release_readiness")
    assert readiness["data"]["passed"] is False
    assert any("tweak" in p for p in readiness["data"]["problems"])
    _, out = orchestrate("status", "R-0001")
    assert "State: paused" in out
    assert not [n for n, p in github.prs.items() if p.head == "docs/run-R-0001-close-out"]


# The delivery board follows each Lane (#58)


def test_a_lanes_ticket_moves_on_the_board_from_in_progress_to_in_review_to_done(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent)
    published_fields = None

    def implement_and_look(request: StepRequest) -> StepResult:
        nonlocal published_fields
        published_fields = dict(github.board[100])  # while the Lane is implementing
        return writes({"feature.txt": "expiry\n"})(request)

    agent.script["implement"] = [implement_and_look]

    pr = through_docs_pr(orchestrate, github, repo, published)

    assert published_fields == {"Status": "In Progress", "Release": "2", "Kind": "Feature"}
    assert github.board[100] == {
        "Status": "In Review",
        "Release": "2",
        "Kind": "Feature",
    }  # waiting for merge
    merge(repo, github, pr)
    orchestrate("resume", "R-0001")
    assert github.board[100] == {"Status": "Done", "Release": "2", "Kind": "Feature"}


def test_a_lane_that_keeps_failing_stays_in_progress_until_rolled_back(
    orchestrate: Orchestrate,
    github: InMemoryGitHub,
    agent: ScriptedAgent,
    repo: Path,
    published: Callable[[], int],
) -> None:
    lane_script(agent, *(writes({"other.txt": "x"}) for _ in range(3)))
    merge(repo, github, published())

    orchestrate("resume", "R-0001")  # verify fails three times: the Lane pauses

    assert github.board[100]["Status"] == "In Progress"
    orchestrate("reject", "R-0001", "lane:T1", "--reason", "Rethink.")
    assert github.board[100] == {"Status": "Todo", "Release": "2", "Kind": "Feature"}


def test_the_default_verify_command_finds_jdk_25_itself() -> None:
    """Exit Gates run `./mvnw` in a Lane's worktree without JAVA_HOME exported (#62)."""
    from orchestrator.settings import Settings

    assert Settings().verify_command.startswith("scripts/with-jdk.sh ")
    shipped = (Path(__file__).resolve().parents[1] / "settings.yaml").read_text()
    assert "verify_command: scripts/with-jdk.sh ./mvnw -B -q verify" in shipped
    script = Path(__file__).resolve().parents[2] / "scripts" / "with-jdk.sh"
    assert script.is_file() and script.stat().st_mode & 0o111
