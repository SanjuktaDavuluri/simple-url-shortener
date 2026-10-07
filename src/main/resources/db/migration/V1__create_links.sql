-- Links: one Short Code naming one Long URL (CONTEXT.md, ADR 0002, ADR 0003).
-- The primary key makes the database itself refuse duplicate Short Codes.
CREATE TABLE links (
    short_code TEXT NOT NULL PRIMARY KEY,
    long_url   TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
