-- Manage Token hash (CONTEXT.md, spec 0005, ADR 0014, ADR 0023): the SHA-256 of a Link's Manage
-- Token as 64 lower-case hex characters. The token itself is never stored.
-- Nullable: Links created before this change have no Manage Token, and keep Redirecting as before.
-- No index: Links are looked up by short_code, the primary key.
ALTER TABLE links ADD COLUMN manage_token_hash TEXT;
