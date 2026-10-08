-- Expiry: the fixed UTC time a Link stops Redirecting (CONTEXT.md, spec 0004, ADR 0022).
-- NULL means the Link has no Lifetime and never expires, so every existing Link keeps Redirecting.
-- Same ISO-8601 UTC format as links.created_at. No index: it is only read through the primary key.
-- Expired Links stay stored, so the primary key keeps refusing their Short Codes. clicks is untouched.
ALTER TABLE links ADD COLUMN expires_at TEXT;
