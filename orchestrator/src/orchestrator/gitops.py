"""Git operations for a Run: its own worktrees and branches, never the engineer's checkout."""

import re
import subprocess
import threading
from pathlib import Path

from orchestrator import docmerge
from orchestrator.policies import Change
from orchestrator.workspace import Workspace

# Parallel Lanes share one repository; concurrent fetches and worktree changes contend for its ref
# locks, so git commands run one at a time (ADR 0020). Agent steps and Exit Gates stay parallel.
_lock = threading.RLock()


def _run(cwd: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    with _lock:
        return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=check)


def _git(cwd: Path, *args: str) -> str:
    return _run(cwd, *args).stdout


def run_branch(run: str) -> str:
    return f"docs/run-{run}"


def tickets_path(run: str) -> str:
    return f"delivery/runs/{run}/tickets.json"


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
    return (
        _run(workspace.repo_root, "cat-file", "-e", f"origin/main:{path}", check=False).returncode
        == 0
    )


def files_on_main(workspace: Workspace, directory: str) -> list[str]:
    listing = _run(
        workspace.repo_root, "ls-tree", "--name-only", "origin/main", f"{directory}/", check=False
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


def remove_lane(workspace: Workspace, name: str, branch: str) -> None:
    """Delete a Lane's worktree, local branch and pushed branch (each only if it exists)."""
    root = workspace.repo_root
    path = workspace.worktrees / name
    if path.exists():
        _git(root, "worktree", "remove", "--force", str(path))
    _run(root, "branch", "-D", branch, check=False)
    _run(root, "push", "--quiet", "origin", "--delete", branch, check=False)


def commit_and_push(tree: Path, run: str, paths: list[str], message: str) -> None:
    _git(tree, "add", "--", *paths)
    if _git(tree, "status", "--porcelain", "--", *paths).strip():
        _git(tree, "commit", "--quiet", "-m", message)
    push(tree, run_branch(run))


def fetch(workspace: Workspace) -> None:
    _git(workspace.repo_root, "fetch", "--quiet", "origin")


def show(workspace: Workspace, ref: str, path: str) -> str | None:
    """A file as it is on `ref` (fetched), or None when it isn't there."""
    result = _run(workspace.repo_root, "show", f"{ref}:{path}", check=False)
    return result.stdout if result.returncode == 0 else None


def bring_in_main(
    tree: Path, message: str, shared: tuple[str, ...] = ()
) -> tuple[list[str], list[str]] | None:
    """Merge origin/main into the worktree's branch when it is behind. Returns None when there
    was nothing to take in, else (conflicting files, shared documents merged row by row). A
    conflict only in `shared` documents is merged row by row (#77, `docmerge`); any other
    conflict undoes the merge and is returned for a human to resolve."""
    _git(tree, "fetch", "--quiet", "origin")
    if (
        _run(tree, "merge-base", "--is-ancestor", "origin/main", "HEAD", check=False).returncode
        == 0
    ):
        return None
    merge = ("-c", "merge.conflictStyle=diff3", "merge", "--quiet", "--no-edit", "-m", message)
    if _run(tree, *merge, "origin/main", check=False).returncode == 0:
        return [], []
    conflicts = _git(tree, "diff", "--name-only", "--diff-filter=U").split()
    if conflicts and set(conflicts) <= set(shared) and _merge_rows(tree, conflicts):
        _git(tree, "commit", "--quiet", "--no-edit")
        return [], conflicts
    _run(tree, "merge", "--abort", check=False)
    return conflicts, []


def _merge_rows(tree: Path, paths: list[str]) -> bool:
    merged = {path: docmerge.merge_rows((tree / path).read_text()) for path in paths}
    if any(text is None for text in merged.values()):
        return False
    for path, text in merged.items():
        (tree / path).write_text(text or "")
        _git(tree, "add", path)
    return True


def bring_in(tree: Path, ref: str, message: str) -> None:
    """Merge `ref` into the worktree's branch (a fast-forward when possible), never rewriting it."""
    _git(tree, "fetch", "--quiet", "origin")
    _git(tree, "merge", "--quiet", "--no-edit", "-m", message, ref)
