"""The CI workflow builds and tests the container on every pull request (spec 0007, ticket #174)."""

import re
from pathlib import Path

CI = (Path(__file__).resolve().parents[2] / ".github" / "workflows" / "ci.yml").read_text()


def _job(name: str) -> str:
    match = re.search(rf"^  {name}:\n(.*?)(?=^  \w[\w-]*:\n|\Z)", CI, re.S | re.M)
    assert match, f"job {name} missing"
    return match.group(1)


def test_required_job_names_unchanged() -> None:
    for name in (
        "Verify (format, compile, analysis, unit + integration tests)",
        "Browser checks (Playwright + Lighthouse)",
        "Orchestrator (lint, types, tests)",
    ):
        assert f"name: {name}\n" in CI


def test_container_job_runs_container_tests_on_own_port() -> None:
    job = _job("container")
    assert "name: Container (image build + container tests)" in job
    assert "scripts/container-test.sh" in job
    assert "docker compose config" in job
    assert re.search(r"PORT: \"?87\d\d", job)


def test_workflow_never_pushes_or_deploys() -> None:
    assert not re.search(r"docker (push|login)|docker/login-action|push: true|deploy", CI, re.I)


def test_pull_requests_trigger_the_workflow() -> None:
    assert re.search(r"^on:\n  pull_request:\n", CI, re.M)
