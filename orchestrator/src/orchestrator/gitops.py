"""Git operations for a Run: its own worktree and branch, never the engineer's checkout or main."""

import subprocess
from pathlib import Path

from orchestrator.workspace import Workspace


def _git(cwd: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True)
    return result.stdout


def run_branch(run: str) -> str:
    return f"docs/run-{run}"


def ensure_run_worktree(workspace: Workspace, run: str) -> Path:
    """The Run's spec and ADRs are written on `docs/run-R-NNNN`, branched from origin/main."""
    path = workspace.worktrees / run
    if not path.exists():
        _git(workspace.repo_root, "fetch", "--quiet", "origin", "main")
        _git(
            workspace.repo_root,
            "worktree",
            "add",
            "--quiet",
            "-b",
            run_branch(run),
            str(path),
            "origin/main",
        )
    return path


def exists_on_main(workspace: Workspace, path: str) -> bool:
    result = subprocess.run(
        ["git", "cat-file", "-e", f"origin/main:{path}"],
        cwd=workspace.repo_root,
        capture_output=True,
    )
    return result.returncode == 0


def commit_and_push(worktree: Path, run: str, paths: list[str], message: str) -> None:
    _git(worktree, "add", "--", *paths)
    if _git(worktree, "status", "--porcelain", "--", *paths).strip():
        _git(worktree, "commit", "--quiet", "-m", message)
    _git(worktree, "push", "--quiet", "-u", "origin", run_branch(run))
