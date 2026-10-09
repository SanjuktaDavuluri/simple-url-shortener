"""Row merge for the shared documents sibling Lanes all append to (#77): the integration-testing
plan's traceability rows and the README's in-progress bullets.

A conflict hunk is merged as a set of rows. A table row is known by its first two cells, any other
line by its text. A row added on either side is kept; a row one side changed or removed takes that
side's version; a row both sides changed differently is a real conflict, and the merge gives up so a
human resolves it.
"""

import re

HUNK = re.compile(
    r"<<<<<<< [^\n]*\n(.*?)\|\|\|\|\|\|\| [^\n]*\n(.*?)=======\n(.*?)>>>>>>> [^\n]*\n", re.S
)


def row_key(line: str) -> str:
    return "|".join(line.split("|")[:3]) if line.startswith("|") else line


def merge_hunk(ours: str, base: str, theirs: str) -> str | None:
    o, b, t = (
        {row_key(line): line for line in side.splitlines(keepends=True)}
        for side in (ours, base, theirs)
    )
    merged: list[str] = []
    for key in dict.fromkeys([*t, *o]):
        mine, was, other = o.get(key), b.get(key), t.get(key)
        if mine == was:
            kept = other
        elif other in (was, mine):
            kept = mine
        else:
            return None
        if kept is not None:
            merged.append(kept)
    return "".join(merged)


def merge_rows(text: str) -> str | None:
    """The file with every diff3 conflict hunk merged row by row, or None if one can't be."""
    hunks = list(HUNK.finditer(text))
    if not hunks or "<<<<<<<" in HUNK.sub("", text):
        return None
    out, end = [], 0
    for hunk in hunks:
        merged = merge_hunk(hunk.group(1), hunk.group(2), hunk.group(3))
        if merged is None:
            return None
        out += [text[end : hunk.start()], merged]
        end = hunk.end()
    return "".join([*out, text[end:]])
