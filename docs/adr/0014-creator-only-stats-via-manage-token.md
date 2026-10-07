---
status: accepted
date: 2026-10-07
---

# Creator-only stats, authorised by a hashed manage token

Analytics adds a stats view per Link: human Clicks, bot Clicks, Clicks per day for the last 30 days, the top 10 referrer hosts, the desktop/mobile split, and the time of the last Click. All of it is aggregated from minimal, non-personal Click data (ADR 0013). Even so, *who is clicking my link and from where* is the link creator's business, not that of everyone the Short URL was shared with. The shortener has no user accounts (roadmap R8: won't do), so access needs a model that doesn't depend on them.

## Decision

- **Creating a Link returns a `manage_token`** (a high-entropy random secret) **once**, in the create response and on the web page's result panel, with a copy button and a "keep this safe" note.
- The token is **stored hashed** (a one-way hash, like a password). A database leak doesn't reveal tokens, and a lost token can't be recovered, only reissued (later, R4).
- `GET /links/{shortCode}/stats` requires `Authorization: Bearer <manage_token>`. Missing or wrong token → `404` (not `401`/`403`), so the endpoint doesn't confirm which Short Codes exist.
- **Tokens never appear in URLs** (URLs leak into logs, browser history and `Referer` headers) and are never logged.
- **Backwards compatible:** the create-Link response gains one field. Existing fields and behaviour are unchanged, so current API clients keep working. Links created before this change have no token, and their stats are available to nobody until a token can be issued (R4).
- The same token is the intended authority for future edit and delete (R4).

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | Public stats for anyone who knows the Short Code | Simplest; no secrets to manage | Everyone the Short URL was shared with can see who clicks it and from where | Rejected |
| B | **Creator-only via a manage token, shown once, stored hashed, sent as a Bearer header** | Protects creators' data without accounts; safe secret handling; enables edit/delete later | The creator must keep the token; a lost token can't be recovered | **Chosen** |
| C | Operator-only, via an admin key in configuration | Simple for the operator | Link creators can never see their own stats | Rejected |
| D | User accounts and login | The most familiar model | Identity, passwords and sessions: a large scope the project has ruled out (R8) | Rejected |

**In short:** a capability token, the simplest access model that gives creators private stats without accounts, handled the way secrets should be handled.

## Consequences

- Token generation uses a cryptographically secure source. Comparison is constant-time against the stored hash.
- Rate limiting (R19) must also cover the stats endpoint, so tokens can't be brute-forced.
- The OpenAPI definition (R21) documents the Bearer scheme. The web page shows the token once only, and refreshing the page doesn't show it again.
