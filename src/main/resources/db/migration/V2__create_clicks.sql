-- Clicks: one row per successful Redirect (CONTEXT.md, spec 0003, ADR 0012, ADR 0013).
-- Only non-personal attributes are kept: no IP address, raw user agent or full referrer URL.
-- clicked_at uses the same ISO-8601 UTC format as links.created_at.
-- No foreign key to links: what happens to Clicks when a Link is deleted belongs to R4.
CREATE TABLE clicks (
    id             INTEGER PRIMARY KEY,
    short_code     TEXT NOT NULL,
    clicked_at     TEXT NOT NULL,
    referrer_host  TEXT,
    agent_category TEXT NOT NULL CHECK (agent_category IN ('browser', 'bot', 'other')),
    device_class   TEXT NOT NULL CHECK (device_class IN ('desktop', 'mobile'))
);

-- Counting and listing a Link's Clicks over time (R2).
CREATE INDEX clicks_short_code_clicked_at ON clicks (short_code, clicked_at);
