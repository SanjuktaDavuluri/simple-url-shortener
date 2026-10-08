---
status: proposed
date: 2026-10-08
---

# Expired Links are kept and their Short Codes are never reused

Spec 0004 (R3, Issue #83) gives a Link an optional **Lifetime** (1–365 whole days), fixed at creation as a UTC **Expiry**. From its Expiry on, the Link is an **Expired Link** and its Short URL answers `410 Gone` instead of Redirecting.

Expiry raises a storage question that the rest of the spec depends on: what happens to an Expired Link's row, its Short Code and its Clicks? Most people expect "expiry" to free something. Here it frees nothing, and the reason isn't obvious from the code. The choice is also hard to undo. Once a Short Code has been given to a new Link, every old copy of that Short URL in emails, documents and bookmarks already sends people to the new Long URL, and no later change can take that back.

The maintainer answered this on Issue #83 (spec 0004, Further Notes, question 3). This ADR records the decision and its trade-off.

## Decision

- **An Expired Link stays stored** in `links`, with its Short Code, Long URL and `expires_at`. Nothing deletes it: no background job, no purge, no grace period.
- **Its Short Code is never reused.** `links.short_code` is still the primary key, so a newly drawn Short Code that names an Expired Link is a Collision like any other and another is drawn (ADR 0003, unchanged).
- **Its Clicks are kept** in `clicks`, for auditing and for R2's stats (ADR 0014).
- **It answers `410 Gone` for as long as it exists** (spec 0004, story 17). It never Redirects again.
- **R4 (edit / delete) must keep this guarantee.** Deleting a Link must still keep its Short Code from being reused, for example by keeping a tombstone row. Allowing reuse there needs a new ADR that supersedes this one.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Keep the Expired Link, retire its Short Code forever, keep its Clicks** | An old Short URL can never quietly point at someone else's Long URL, so stale links in emails and bookmarks can't become a phishing or malware path. No new code: the existing primary key already refuses taken Short Codes. No background job, schedule or setting. Clicks stay available for auditing and R2. A Redirect cache (ADR 0019 stage 3) can treat Links as immutable | `links` and `clicks` only ever grow; expiry frees no rows and no Short Codes. Expired rows stay in the primary-key index the Redirect lookup uses. Personal data in a Long URL (for example a token in a query string) is kept after the Link expires, until R4 adds deletion | **Chosen** |
| B | Delete the Expired Link after a grace period and let its Short Code be reused | Bounded storage; the Short Code space is recycled | Anyone holding the old Short URL is sent to an unrelated Long URL with no warning. That is the kind of hijack a shortener exists to prevent, and it can't be undone after the fact. Needs a scheduled purge job, a grace-period setting, and a decision about the Clicks. Recycling buys nothing: ADR 0003's 62^7 (≈ 3.5 × 10¹²) Short Codes won't run out at this project's scale | Rejected: it makes a rare storage problem into a safety problem |
| C | Delete the Expired Link and its Clicks, but keep a list of retired Short Codes | Long URLs and Clicks are removed (smaller tables, less data kept); Short Codes are still never reused | A second table (or a tombstone flag) that every Short Code draw has to check, so the Collision check is no longer just the primary key. Still needs a purge job and a grace period. The Clicks history that R2 and auditing want is lost. Saves about one row per Expired Link, which isn't a problem at today's scale | Rejected for now: more moving parts for a saving nobody needs yet. R4 can adopt this tombstone shape for deletion without breaking this ADR |

The response code (`410` rather than `404` or a `302` to an "expired" page) is not part of this decision. It is easy to change, and spec 0004 explains it (story 12, Further Notes).

**In short:** we accept that `links` and `clicks` grow without bound, in exchange for a guarantee that a Short URL, once shared, can only ever reach the Long URL it was created for, or `410`.

## Consequences

- **Storage grows exactly as it does today.** Expiry neither adds nor frees rows; each Link with a Lifetime adds one nullable timestamp (`expires_at`). Storage growth is watched like any other (R12); if it ever matters, the answer is R4's deletion with tombstones (option C) or the move to PostgreSQL (R5, ADR 0019), not Short Code reuse.
- **No new code enforces the guarantee.** The primary key on `links.short_code` already refuses a taken Short Code, Expired or not. A Collision test with the scripted generator proves that a Short Code naming an Expired Link is drawn again and the Expired Link still answers `410` (spec 0004, Testing Decisions).
- **The Redirect stays one primary-key lookup.** Expired rows stay in the index, which costs at most a slightly deeper B-tree, with no extra query. R13 can include Expired Links in its load mix.
- **Data kept after expiry.** An Expired Link's Long URL and Clicks stay stored. Clicks hold no personal data (ADR 0013), but a Long URL may carry some. Removing it is R4's job, and R4 must do it without freeing the Short Code.
- **Constrains R4 (edit / delete).** Deleting a Link must keep its Short Code retired, and changing or removing an Expiry after creation is out of scope until R4 decides it. Any form of Short Code reuse needs a new ADR that supersedes this one.
- **Helps ADR 0019 stage 3 (Redirect cache).** Expiry is fixed at creation and Short Codes are never reassigned, so a cache entry (Long URL + Expiry) is never invalidated. It is checked against the clock on every hit.
- **Glossary.** `CONTEXT.md` defines **Expired Link** as stored, never reused, answering `410 Gone` (spec 0004, glossary candidates).
