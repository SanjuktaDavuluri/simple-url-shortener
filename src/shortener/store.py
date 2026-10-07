"""The Link Store: the only module that touches storage (ADR 0002)."""

import sqlite3
from contextlib import closing
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS links (
    short_code TEXT PRIMARY KEY,
    long_url   TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
)
"""


class LinkStore:
    """Stores Links in SQLite. The primary key makes the database itself refuse duplicate
    Short Codes."""

    def __init__(self, db_path: Path) -> None:
        self._db_path = db_path
        with closing(self._connect()) as conn, conn:
            conn.execute(_SCHEMA)

    def save(self, short_code: str, long_url: str) -> None:
        with closing(self._connect()) as conn, conn:
            conn.execute(
                "INSERT INTO links (short_code, long_url) VALUES (?, ?)", (short_code, long_url)
            )

    def get(self, short_code: str) -> str | None:
        with closing(self._connect()) as conn:
            row = conn.execute(
                "SELECT long_url FROM links WHERE short_code = ?", (short_code,)
            ).fetchone()
        return row[0] if row else None

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self._db_path)
