"""Delivery metrics (spec 0002, story 52; ADR 0009), computed from committed Event Logs only.

Definitions, from spec 0002:
- success rate: Runs fully delivered ÷ Runs that finished
- retry frequency: `retry` events ÷ Stage executions (`stage_started`, one per Lane for Lane Stages)
- rollback frequency: rolled-back Lanes ÷ Lanes started
- MTTR: mean time from a failed gate to the next passing gate of the same Stage and Lane
- end-to-end latency: `run_started` → `run_finished`, in total and without time awaiting humans:
  the union of open approvals, open questions, pauses and Safe-stops (parallel Lanes can wait at
  the same time, so each second is counted once)
- cost per Run: the sum of its `agent_call` costs

A log that fails `orchestrate verify` is ignored, and the output says so.
"""

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from statistics import mean
from typing import Any

from orchestrator.events import EventLog, verify

Event = dict[str, Any]
Interval = tuple[float, float]


@dataclass(frozen=True)
class RunMetrics:
    run: str
    outcome: str
    latency: float
    human_wait: float
    stage_executions: int
    retries: int
    lanes: int
    rollbacks: int
    recoveries: tuple[float, ...]
    cost: float


def _seconds(event: Event, start: datetime) -> float:
    return (datetime.fromisoformat(event["ts"]) - start).total_seconds()


def _waits(events: list[Event], start: datetime) -> list[Interval]:
    """Every span the Run spent waiting for a human; one left open ends with the Run."""
    end = _seconds(events[-1], start)
    spans: list[Interval] = []
    opened: dict[tuple[str, Any], float] = {}

    def close(key: tuple[str, Any], at: float) -> None:
        if key in opened:
            spans.append((opened.pop(key), at))

    for e in events:
        at, data = _seconds(e, start), e["data"]
        match e["type"]:
            case "approval_requested":
                opened.setdefault(("approval", data["checkpoint"]), at)
            case "approved" | "rejected" | "approval_withdrawn":
                close(("approval", data["checkpoint"]), at)
            case "question_asked":
                opened.setdefault(("question", data["n"]), at)
            case "answer_received":
                close(("question", data["n"]), at)
            case "paused":
                opened.setdefault(("paused", data.get("lane")), at)
            case "safe_stop":
                opened.setdefault(("stopped", None), at)
            case "resumed":
                close(("paused", data.get("lane")), at)
                close(("stopped", None), at)
            case "lane_rolled_back":
                close(("paused", data["lane"]), at)
    spans += [(at, end) for at in opened.values()]
    return spans


def _union(spans: list[Interval]) -> float:
    total, reach = 0.0, float("-inf")
    for begin, finish in sorted(spans):
        begin = max(begin, reach)
        if finish > begin:
            total += finish - begin
        reach = max(reach, finish)
    return total


def _recoveries(events: list[Event], start: datetime) -> list[float]:
    failed: dict[tuple[str, Any], float] = {}
    found: list[float] = []
    for e in events:
        if e["type"] != "gate_result":
            continue
        key = (e["stage"], e["data"].get("lane"))
        if not e["data"]["passed"]:
            failed.setdefault(key, _seconds(e, start))
        elif key in failed:
            found.append(_seconds(e, start) - failed.pop(key))
    return found


def run_metrics(run: str, events: list[Event]) -> RunMetrics | None:
    """One finished Run's figures; None while it hasn't finished."""
    finished = next((e for e in events if e["type"] == "run_finished"), None)
    if finished is None:
        return None
    start = datetime.fromisoformat(events[0]["ts"])

    def count(type_: str) -> int:
        return sum(1 for e in events if e["type"] == type_)

    return RunMetrics(
        run=run,
        outcome=str(finished["data"].get("outcome", "delivered")),
        latency=_seconds(finished, start),
        human_wait=_union(_waits(events, start)),
        stage_executions=count("stage_started"),
        retries=count("retry"),
        lanes=count("lane_started"),
        rollbacks=count("lane_rolled_back"),
        recoveries=tuple(_recoveries(events, start)),
        cost=sum(float(e["data"].get("cost_usd", 0)) for e in events if e["type"] == "agent_call"),
    )


@dataclass(frozen=True)
class Collected:
    runs: list[RunMetrics]
    unfinished: list[str]
    ignored: dict[str, str]  # run → why its log failed verification


def collect(runs_dir: Path) -> Collected:
    runs: list[RunMetrics] = []
    unfinished: list[str] = []
    ignored: dict[str, str] = {}
    for path in sorted(runs_dir.glob("R-*/events.jsonl")):
        run = path.parent.name
        check = verify(path)
        if not check.intact:
            ignored[run] = check.problem or "broken"
            continue
        measured = run_metrics(run, EventLog(path).read())
        if measured is None:
            unfinished.append(run)
        else:
            runs.append(measured)
    return Collected(runs, unfinished, ignored)


def duration(seconds: float) -> str:
    s = round(seconds)
    if s < 60:
        return f"{s}s"
    if s < 3600:
        return f"{s // 60}m {s % 60:02d}s"
    return f"{s // 3600}h {s // 60 % 60:02d}m"


def _percent(part: int, whole: int) -> str:
    if not whole:
        return "—"
    value = 100 * part / whole
    return f"{value:.0f}%" if value == int(value) else f"{value:.1f}%"


def summary(c: Collected) -> list[tuple[str, str]]:
    runs = c.runs
    delivered = sum(1 for r in runs if r.outcome == "delivered")
    retries, executions = sum(r.retries for r in runs), sum(r.stage_executions for r in runs)
    rollbacks, lanes = sum(r.rollbacks for r in runs), sum(r.lanes for r in runs)
    recoveries = [t for r in runs for t in r.recoveries]
    costs = [r.cost for r in runs]
    return [
        (
            "Success rate",
            f"{_percent(delivered, len(runs))} ({delivered} of {len(runs)} Runs fully delivered)",
        ),
        (
            "Retry frequency",
            f"{_percent(retries, executions)} ({retries} retries in {executions} Stage executions)",
        ),
        (
            "Rollback frequency",
            f"{_percent(rollbacks, lanes)} ({rollbacks} of {lanes} Lanes rolled back)",
        ),
        (
            "MTTR",
            f"{duration(mean(recoveries))} ({len(recoveries)} recoveries)" if recoveries else "—",
        ),
        (
            "End-to-end latency",
            f"{duration(mean(r.latency for r in runs))} mean; "
            f"{duration(mean(r.latency - r.human_wait for r in runs))} excluding human wait",
        ),
        ("Cost per Run", f"${mean(costs):.2f} mean (${sum(costs):.2f} in all)"),
    ]


def render(c: Collected) -> str:
    lines = [
        "# Delivery metrics",
        "",
        "Generated by `orchestrate metrics` from the committed Event Logs in `delivery/runs/`. "
        "Definitions: [spec 0002](../docs/specs/0002-delivery-orchestrator.md) (metrics "
        "definitions). Logs that fail `orchestrate verify` are not counted.",
        "",
    ]
    if not c.runs:
        lines += ["No finished Runs yet.", ""]
    else:
        lines += ["| Metric | Value |", "|---|---|"]
        lines += [f"| {name} | {value} |" for name, value in summary(c)]
        lines += [
            "",
            "## Runs",
            "",
            "| Run | Outcome | Latency | Excluding human wait | Retries | Rollbacks | Cost |",
            "|---|---|---|---|---|---|---|",
        ]
        lines += [
            f"| {r.run} | {r.outcome} | {duration(r.latency)} | "
            f"{duration(r.latency - r.human_wait)} | {r.retries} | {r.rollbacks} | ${r.cost:.2f} |"
            for r in c.runs
        ]
        lines.append("")
    if c.unfinished or c.ignored:
        lines += ["## Not counted", "", "| Run | Why |", "|---|---|"]
        lines += [f"| {run} | not finished |" for run in c.unfinished]
        lines += [f"| {run} | {why} |" for run, why in c.ignored.items()]
        lines.append("")
    return "\n".join(lines)


def regenerate(repo_root: Path) -> Collected:
    """Rewrite `delivery/metrics.md` from `delivery/runs/` under `repo_root`."""
    collected = collect(repo_root / "delivery" / "runs")
    target = repo_root / "delivery" / "metrics.md"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(render(collected))
    return collected
