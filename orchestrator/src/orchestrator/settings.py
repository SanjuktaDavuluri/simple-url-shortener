"""Run settings: defaults from spec 0002, overridden by `orchestrator/settings.yaml`."""

from dataclasses import asdict, dataclass, fields, replace
from pathlib import Path
from typing import Any

import yaml


@dataclass(frozen=True)
class Settings:
    max_retries: int = 2
    max_parallel_lanes: int = 2
    cost_cap_step_usd: float = 5.0
    cost_cap_run_usd: float = 50.0
    model: str = "claude-opus-5-5"

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def load_settings(repo_root: Path) -> Settings:
    path = repo_root / "orchestrator" / "settings.yaml"
    if not path.exists():
        return Settings()
    raw = yaml.safe_load(path.read_text()) or {}
    known = {f.name: f.type for f in fields(Settings)}
    unknown = set(raw) - set(known)
    if unknown:
        raise ValueError(f"unknown settings in {path}: {', '.join(sorted(unknown))}")
    defaults = Settings()
    values = {k: type(getattr(defaults, k))(v) for k, v in raw.items()}
    return replace(defaults, **values)
