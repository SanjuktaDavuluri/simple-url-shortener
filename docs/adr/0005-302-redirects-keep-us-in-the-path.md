---
status: accepted
date: 2026-10-07
---

# Redirect with 302 Found so every visit passes through the shortener

`GET /{code}` answers with **302 Found** and a `Location` header. We do **not** use 301 Moved Permanently. We want the shortener to stay **in the path of every redirect**: each click must reach our server, not be served from a browser's or proxy's cache. That is what makes the link something we control and can observe. It is also the foundation for planned features: click counts (R2), expiring links (R3), edit/delete (R4), and a clickstream audit pipeline built on successful redirects (R10).

## Why this is deliberate

A common belief is that "URL shorteners use 301", because 301 is cacheable and cheap to serve. A reader may be tempted to "optimise" this into a 301. **Don't.** A 301 cannot be taken back: once a browser has cached it, that visitor never reaches us again for that link, whatever we change later.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **302 Found** | Every visit reaches us, so we can count, audit, expire, edit or disable a link at any time; clients follow it universally | One request to our server per click, a little more load and latency than a cached redirect | **Chosen** |
| B | **301 Moved Permanently** | Browsers and proxies cache it, so repeat visits skip our server: least load, fastest repeat clicks | We go blind to repeat visits; R2, R3, R4 and R10 break silently; a bad link can't be fixed for clients that already cached it, and this can't be undone | Rejected: we would trade control and observability for performance we don't need |
| C | **307 Temporary Redirect** | Same "stay in the path" property as 302; stricter about keeping the HTTP method | Nothing material for us: redirects are triggered by `GET`, where 302 and 307 behave the same; 302 is more widely recognised | Rejected: no benefit over 302 for GET-only redirects |
| D | **302 + `Cache-Control` tuning** (e.g. short `max-age`) | Some cache offload while keeping occasional control | Partial blindness by design; complicates counting and expiry semantics | Rejected for v1; revisit only if redirect load becomes a real reliability problem |

**In short:** we chose **302 over 301** because control and observability of every click are worth more to this system than the saved request. We chose **302 over 307** because they are equivalent for GET and 302 is the more common convention. We chose **302 over cache-tuned redirects** because there is no load problem yet to justify the blind spots.

## Consequences

- Every click costs one request to our server. That redirect path becomes the system's hot path, which matters for the reliability phase (latency, availability, PostgreSQL migration R5).
- Responses should be explicitly non-cacheable (`Cache-Control: no-store` or similar), so intermediaries don't cache the redirect anyway.
- The redirect handler is the natural place to emit a **click event** later (R10). It must stay a single, well-defined point in the code that every successful redirect passes through.
