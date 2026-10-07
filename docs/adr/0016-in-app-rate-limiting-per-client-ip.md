---
status: accepted
date: 2026-10-07
---

# In-app rate limiting per client IP; IPs are used in memory and never stored

Anyone can create Links, and anyone holding a Short Code can try manage tokens against the stats endpoint (ADR 0014). Without limits, one client could flood the Link Store, fill the disk, or brute-force tokens. We need abuse protection that works **as the service actually runs today**: a single instance with no proxy or gateway in front of it.

## Decision

- **Token-bucket limits per client IP, inside the application**, on:
  - **Link creation:** `POST /links` and the web form (`POST /`). For example, 10 per minute per IP with a small burst.
  - **Stats:** `GET /links/{shortCode}/stats`. A stricter limit, so manage tokens can't be brute-forced.
- **Redirects are not limited** (or only with a very generous ceiling). They are the product, and cheap to serve.
- **When a limit is exceeded:** `429 Too Many Requests` with a `Retry-After` header and a problem detail. The web page shows the same message inline (HTMX swaps `429`, like `422`/`503`).
- **Client IP handling:** the IP is used **only in memory** to select a bucket. It is **never stored, logged or included in events or metrics**, consistent with ADR 0013, which forbids *storing* IPs. Buckets expire when idle.
- **Proxies:** `X-Forwarded-For` is trusted **only** from configured proxy addresses, so a client can't fake its IP to dodge limits. With no proxy configured, the socket address is used.
- Limits are **configuration**, and every limit hit is counted in a metric (ADR 0015).

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **In-app token buckets per client IP; IP in memory only; trusted-proxy handling** | Protects the service as it really runs; no new infrastructure; consistent with the privacy decision | With several instances, each enforces its own share of the limit; per-IP limits can be unfair to many users behind one NAT | **Chosen** |
| B | Limits at the edge (reverse proxy, API gateway, WAF) | Where large systems do it; stops abuse before it reaches the app | No edge exists in this deployment; the app is unprotected when run directly | Rejected for now; part of the scaling path (R25) |
| C | Shared limit store (e.g. Redis) | Correct limits across instances | New infrastructure for a single-node service | Rejected for now; part of the scaling path (R25) |
| D | Per-token or per-account limits | Fairer per user | No accounts (R8); tokens exist only after creation | Rejected |

**In short:** protect what we actually run, use the client IP only as long as it takes to count a request, and leave multi-instance limits to the scaling path.

## Consequences

- **Known limit:** with *N* instances behind a load balancer, the effective limit is up to *N* times the configured value. R25 records moving limits to the edge or a shared store as part of scaling out.
- Tests drive limits with configured low values and an injectable clock, rather than sleeping.
- The trusted-proxy list is configuration. Misconfiguring it (trusting every proxy) would let clients spoof IPs, so the default trusts none.
