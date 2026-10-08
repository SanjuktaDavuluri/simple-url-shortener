"""Run settings: defaults from spec 0002, overridden by `orchestrator/settings.yaml`."""

from dataclasses import asdict, dataclass, field, fields, replace
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
    effort: str = "high"
    stage_models: dict[str, str] = field(default_factory=dict)  # model per step, else `model`
    board_owner: str = ""
    board_number: int = 0
    # Exit Gate commands, run inside a Lane's worktree (never the engineer's checkout)
    verify_command: str = "scripts/with-jdk.sh ./mvnw -B -q verify"  # finds JDK 25 itself
    web_paths: str = "src/main/resources/templates/,src/main/resources/static/"
    app_start_command: str = "scripts/local.sh start"
    app_stop_command: str = "scripts/local.sh stop"
    browser_check_command: str = "cd e2e && npm ci --silent && npm run browser-checks"

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


AGENT_STEPS = ("requirements", "design", "decompose", "implement", "document")
EFFORTS = ("low", "medium", "high", "xhigh", "max")


def check(settings: Settings) -> Settings:
    """Model routing must name real agent steps and a known effort (ADR 0024)."""
    if settings.effort not in EFFORTS:
        raise ValueError(f"effort must be one of {', '.join(EFFORTS)}, not {settings.effort!r}")
    unknown = set(settings.stage_models) - set(AGENT_STEPS)
    if unknown:
        raise ValueError(
            f"stage_models names unknown agent steps: {', '.join(sorted(unknown))}"
            f" (known: {', '.join(AGENT_STEPS)})"
        )
    if not all(
        isinstance(m, str) and m.startswith("claude-") for m in settings.stage_models.values()
    ):
        raise ValueError("every stage_models value must be a Claude model ID (claude-…)")
    return settings


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
    return check(replace(defaults, **values))
