"""Content hashes that ignore formatting-only differences (ADR 0011)."""

import hashlib


def normalise(text: str) -> str:
    """Unify line endings, drop trailing whitespace on each line and at the end."""
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    return "\n".join(line.rstrip() for line in lines).strip("\n")


def content_hash(text: str) -> str:
    return hashlib.sha256(normalise(text).encode("utf-8")).hexdigest()
