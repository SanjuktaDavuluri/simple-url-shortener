"""Git operations for a Run: its own worktrees and branches, never the engineer's checkout."""

import re
import subprocess
from pathlib import Path

from orchestrator.policies import Change
from orchestrator.workspace import Workspace


def _git(cwd: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True)
    return result.stdout


def run_branch(run: str) -> str:
    return f"docs/run-{run}"


def lane_branch(issue: int, title: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:48].rstrip("-")
    return f"feat/{issue}-{slug}"


def worktree(workspace: Workspace, name: str, branch: str) -> Path:
    """A worktree on `branch`, created from the latest origin/main the first time it is needed."""
    path = workspace.worktrees / name
    if not path.exists():
        _git(workspace.repo_root, "fetch", "--quiet", "origin", "main")
        _git(
            workspace.repo_root,
            "worktree",
            "add",
            "--quiet",
            "-b",
            branch,
            str(path),
            "origin/main",
        )
    return path


def ensure_run_worktree(workspace: Workspace, run: str) -> Path:
    """The Run's spec and ADRs are written on `docs/run-R-NNNN`, branched from origin/main."""
    return worktree(workspace, run, run_branch(run))


def exists_on_main(workspace: Workspace, path: str) -> bool:
    result = subprocess.run(
        ["git", "cat-file", "-e", f"origin/main:{path}"],
        cwd=workspace.repo_root,
        capture_output=True,
    )
    return result.returncode == 0


def files_on_main(workspace: Workspace, directory: str) -> list[str]:
    listing = subprocess.run(
        ["git", "ls-tree", "--name-only", "origin/main", f"{directory}/"],
        cwd=workspace.repo_root,
        capture_output=True,
        text=True,
    )
    return listing.stdout.split()


def uncommitted_changes(tree: Path) -> list[Change]:
    """What the last step changed in the worktree, with the lines it added, for the policy check."""
    _git(tree, "add", "-A", "--intent-to-add")
    diff = _git(tree, "diff", "HEAD", "--no-color", "--unified=0", "--no-renames")
    changes: dict[str, list[str]] = {}
    current = ""
    for line in diff.splitlines():
        if line.startswith("diff --git "):
            current = line.split(" b/", 1)[1]
            changes[current] = []
        elif line.startswith("+") and not line.startswith("+++") and current:
            changes[current].append(line[1:])
    return [Change(path, "\n".join(added)) for path, added in changes.items()]


def commit_all(tree: Path, message: str) -> bool:
    """Commit everything changed in the worktree; False when there was nothing to commit."""
    _git(tree, "add", "-A")
    if not _git(tree, "status", "--porcelain").strip():
        return False
    _git(tree, "commit", "--quiet", "-m", message)
    return True


def changed_since_main(tree: Path) -> list[str]:
    _git(tree, "fetch", "--quiet", "origin", "main")
    return _git(tree, "diff", "--name-only", "origin/main...HEAD").split()


def push(tree: Path, branch: str) -> str:
    """Push the branch; returns the pushed commit."""
    _git(tree, "push", "--quiet", "-u", "origin", branch)
    return _git(tree, "rev-parse", "HEAD").strip()


def commit_and_push(tree: Path, run: str, paths: list[str], message: str) -> None:
    _git(tree, "add", "--", *paths)
    if _git(tree, "status", "--porcelain", "--", *paths).strip():
        _git(tree, "commit", "--quiet", "-m", message)
    push(tree, run_branch(run))
