"""Where Runs live (ADR 0009).

Working state stays local in `.orchestrator/`; committed evidence lives in `delivery/`.
"""

import re
from pathlib import Path

from orchestrator.events import EventLog

RUN_ID = re.compile(r"^R-(\d{4})$")


class Workspace:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root
        self.state_dir = repo_root / ".orchestrator"
        self.local_runs = self.state_dir / "runs"
        self.committed_runs = repo_root / "delivery" / "runs"

    def run_ids(self) -> list[str]:
        ids: set[str] = set()
        for base in (self.local_runs, self.committed_runs):
            if base.is_dir():
                ids.update(p.name for p in base.iterdir() if RUN_ID.match(p.name))
        return sorted(ids)

    def next_run_id(self) -> str:
        numbers = [int(m.group(1)) for r in self.run_ids() if (m := RUN_ID.match(r))]
        return f"R-{max(numbers, default=0) + 1:04d}"

    def log_path(self, run: str) -> Path:
        committed = self.committed_runs / run / "events.jsonl"
        local = self.local_runs / run / "events.jsonl"
        return committed if committed.exists() and not local.exists() else local

    def log(self, run: str) -> EventLog:
        return EventLog(self.log_path(run))

    def exists(self, run: str) -> bool:
        return run in self.run_ids()

    def active_run_for_issue(self, issue: int) -> str | None:
        for run in self.run_ids():
            log = self.log(run).read()
            started = next((e for e in log if e["type"] == "run_started"), None)
            finished = any(e["type"] == "run_finished" for e in log)
            if started and started["data"].get("issue") == issue and not finished:
                return run
        return None

    @property
    def checkpoint_db(self) -> Path:
        return self.state_dir / "state.db"
