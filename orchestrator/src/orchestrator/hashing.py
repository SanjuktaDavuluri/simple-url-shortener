"""Content hashes that ignore formatting-only differences (ADR 0011)."""

import hashlib


def normalise(text: str) -> str:
    """Unify line endings, drop trailing whitespace on each line and at the end."""
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    return "\n".join(line.rstrip() for line in lines).strip("\n")


def content_hash(text: str) -> str:
    return hashlib.sha256(normalise(text).encode("utf-8")).hexdigest()


def spec_hash(text: str) -> str:
    """A spec's content hash without its `status:` line: marking a spec implemented, or any other
    lifecycle change to its status, is not a change to what was approved (#170)."""
    head, rest = text.split("\n---\n", 1) if text.startswith("---\n") else ("", text)
    if head:
        head = "\n".join(ln for ln in head.splitlines() if not ln.startswith("status:"))
        text = f"{head}\n---\n{rest}"
    return content_hash(text)
