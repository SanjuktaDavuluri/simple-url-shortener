"""Checks on a spec amendment before it is put in front of the engineer (#170).

An agent proposes the whole amended spec. It is not asked about unless it is a full spec that
differs from the approved one, and the request shows the diff, so a placeholder or a truncated
spec can never be approved by mistake.
"""

import difflib
import re

from orchestrator.hashing import content_hash

FRONT_MATTER = re.compile(r"\A---\n(.*?\n)?---\n", re.DOTALL)
HEADING = re.compile(r"^#{1,6} .+$", re.MULTILINE)
DIFF_LINES = 80


def _keys(text: str) -> set[str] | None:
    match = FRONT_MATTER.match(text)
    if not match:
        return None
    return {
        line.split(":")[0].strip() for line in (match.group(1) or "").splitlines() if ":" in line
    }


def problems(approved: str, amended: str) -> list[str]:
    """Why `amended` is not a full spec that differs from the `approved` one; empty if it is."""
    found: list[str] = []
    wanted, got = _keys(approved), _keys(amended)
    if wanted is not None and got is None:
        found.append(
            "The amendment has no front matter. It must be the whole spec, starting with the "
            "front matter block (`---` … `---`) the approved spec has."
        )
    elif wanted and got is not None and wanted - got:
        found.append(f"The amendment dropped front matter keys: {', '.join(sorted(wanted - got))}")
    missing = [h for h in HEADING.findall(approved) if h not in HEADING.findall(amended)]
    if missing:
        found.append(
            f"The amendment dropped headings of the approved spec: {', '.join(missing)}. "
            "It must be the whole amended spec, not a summary or a placeholder."
        )
    if not found and content_hash(approved) == content_hash(amended):
        found.append("The amendment changes nothing in the approved spec.")
    return found


def diff(approved: str, amended: str) -> tuple[str, str]:
    """(a one-line summary, the unified diff cut to its first lines)."""
    lines = list(
        difflib.unified_diff(
            approved.splitlines(), amended.splitlines(), "approved", "amended", n=2, lineterm=""
        )
    )
    added = sum(1 for ln in lines if ln.startswith("+") and not ln.startswith("+++"))
    removed = sum(1 for ln in lines if ln.startswith("-") and not ln.startswith("---"))
    summary = f"{added} line{'' if added == 1 else 's'} added, {removed} removed"
    if len(lines) > DIFF_LINES:
        lines = [*lines[:DIFF_LINES], f"… {len(lines) - DIFF_LINES} more diff lines in the file"]
    return summary, "\n".join(lines)
