"""The Run's Event Log (ADR 0009).

Append-only JSON lines. Each event carries its own hash and the hash of the event before it.
"""

import hashlib
import json
import threading
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
GENESIS = "0" * 64

Event = dict[str, Any]

# Parallel Lanes append from several threads; one writer at a time keeps the hash chain intact.
_lock = threading.RLock()


def _event_hash(event: Event) -> str:
    body = {k: v for k, v in event.items() if k != "hash"}
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class EventLog:
    def __init__(self, path: Path) -> None:
        self.path = path

    def read(self) -> list[Event]:
        with _lock:
            if not self.path.exists():
                return []
            text = self.path.read_text()
        return [json.loads(line) for line in text.splitlines() if line.strip()]

    def append(
        self,
        *,
        run: str,
        actor: str,
        type: str,
        stage: str | None = None,
        data: Event | None = None,
    ) -> Event:
        with _lock:
            return self._append(run, actor, type, stage, data)

    def _append(
        self, run: str, actor: str, type: str, stage: str | None, data: Event | None
    ) -> Event:
        existing = self.read()
        event: Event = {
            "schema_version": SCHEMA_VERSION,
            "seq": len(existing) + 1,
            "ts": datetime.now(UTC).isoformat(timespec="milliseconds"),
            "run": run,
            "actor": actor,
            "stage": stage,
            "type": type,
            "data": data or {},
            "prev_hash": existing[-1]["hash"] if existing else GENESIS,
        }
        event["hash"] = _event_hash(event)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(event, ensure_ascii=False) + "\n")
        return event


@dataclass(frozen=True)
class Verification:
    intact: bool
    events: int
    problem: str | None = None


def verify(path: Path) -> Verification:
    """Check sequence numbers, each event's own hash, and the link to the previous event."""
    try:
        log = EventLog(path).read()
    except json.JSONDecodeError as e:
        return Verification(False, 0, f"line {e.lineno} is not valid JSON")
    prev = GENESIS
    for expected_seq, event in enumerate(log, start=1):
        seq = event.get("seq")
        if seq != expected_seq:
            return Verification(
                False, len(log), f"event {expected_seq} is missing (found event {seq})"
            )
        if event.get("hash") != _event_hash(event):
            return Verification(False, len(log), f"event {seq} was changed after it was written")
        if event.get("prev_hash") != prev:
            return Verification(
                False, len(log), f"event {seq} does not follow event {expected_seq - 1}"
            )
        prev = event["hash"]
    return Verification(True, len(log))
