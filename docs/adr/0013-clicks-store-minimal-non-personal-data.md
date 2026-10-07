---
status: accepted
date: 2026-10-07
---

# Clicks store minimal, non-personal data

Click events (ADR 0012) power the analytics features. Collected data is hard to "un-collect": personal data brings legal duties (GDPR-style rights, retention and deletion) and becomes a security liability. So we decide up front what a Click may contain, and we choose **privacy by design**: enough to answer the analytics questions, nothing that identifies a person.

## Decision

| Field | Stored as |
|---|---|
| Short Code | As is |
| Time | UTC timestamp |
| Referrer | **Host only** (`news.example.com`), never the full URL, which can carry private query strings. Absent if there is no referrer |
| User agent | **Category only**: `browser`, `bot` or `other`, plus device class `desktop` or `mobile`. Never the raw user-agent string |
| IP address | **Never stored**, not even hashed |

- **Bots** (known crawlers and link-preview fetchers) are flagged `bot`, counted separately, and **excluded from the headline Click count**. They are kept, not discarded, so the counting decision can be revisited without losing data.
- The raw user-agent and referrer are used only in memory, to derive the category and host, and are never logged.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Minimal: host-only referrer, user-agent category, no IP, bots flagged** | Answers every planned question (how many, when, from which sites, desktop or mobile, humans or bots) while holding no personal data | Some analysis is impossible later: unique visitors, geography, exact referring pages | **Chosen** |
| B | Full raw: IP, full user agent, full referrer URL | Maximum flexibility for future analysis | Personal data: retention rules, deletion requests, a stronger security posture, and a liability if leaked | Rejected |
| C | Counters only, with no Click rows | Cheapest | No time series, referrers or device split; no raw events to audit or recount | Rejected |
| D | Store IPs hashed or truncated | Rough uniqueness and geography | Hashed IPs are still personal data in many regimes (they are easy to reverse by brute force); the compliance burden remains | Rejected |

**In short:** collect only what the product needs today. Adding a field later is easy, but removing personal data you already hold is not.

## Consequences

- **Do not add IP addresses, raw user agents or full referrer URLs** without a new ADR that covers retention, deletion and security.
- Bot classification uses a maintained list of crawler and link-preview patterns, with its own unit tests. Misclassification affects counts, not Redirects.
- "Unique visitors" and geography are explicitly **not** offered by the stats API.
