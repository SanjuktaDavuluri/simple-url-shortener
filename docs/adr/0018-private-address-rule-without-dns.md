---
status: accepted
date: 2026-10-07
---

# The private-address Rule checks addresses as written, without a DNS lookup

**The shortener never fetches Long URLs. It only redirects visitors' browsers to them.** So the realistic abuse is not server-side request forgery against our own server. It is someone using a trusted-looking Short URL to send a **visitor's browser** to addresses inside *the visitor's* network: a home router (`http://192.168.1.1/admin`), a local service (`http://localhost:8080`), or cloud metadata (`http://169.254.169.254/`). We add a Rule (ADR 0004) that refuses such Long URLs.

## Decision

A new **`PrivateAddressRule`**, placed after the host check in the v1 Rule Set, rejects a Long URL whose host is:

- **An IP literal in a non-public range:** private (RFC 1918), loopback, link-local (including cloud metadata `169.254.169.254`), carrier-grade NAT, unspecified, multicast, broadcast, and reserved ranges, for **IPv4 and IPv6** (including unique-local `fc00::/7` and IPv4-mapped/compatible IPv6).
- **A disguised IP literal,** normalised before checking: decimal (`http://2130706433/`), hexadecimal, octal and shortened forms (`http://127.1/`).
- **A reserved name:** `localhost` and `*.localhost`, `*.local`, `*.internal`, `*.home.arpa`, and well-known cloud metadata hostnames.

The Rejection Reason: *"Links to private or internal network addresses aren't allowed."*

**No DNS lookup is made.** A hostname that resolves to a private address (e.g. `intranet.example.com → 10.0.0.5`) is not detected.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Check the host as written: IP literals (normalised, every encoding) and reserved names; no DNS** | Catches the realistic abuse (obvious internal targets and disguised IP encodings) with no latency, no network dependency, deterministic tests | Misses public hostnames that resolve to private addresses | **Chosen** |
| B | A, plus resolve DNS at creation and reject private results | Also catches `intranet.example.com → 10.x` | Latency and an external dependency on every creation; false rejections for names that don't resolve *from our network*; defeated anyway by DNS rebinding after creation | Rejected: real cost for protection rebinding defeats |
| C | No Rule | Nothing to build | The shortener becomes a convenient way to disguise links to internal networks | Rejected |

**In short:** match the defence to the actual threat. Because we redirect rather than fetch, checking what the URL literally says catches the realistic attacks at no cost, and a DNS check would buy little real protection.

## Consequences

- **If a feature ever fetches Long URLs server-side** (link previews, availability checks, metadata scraping), it needs full SSRF protection at fetch time: resolve, pin the resolved IP, re-check it, and block redirects to private ranges. This Rule is not enough for that, and a new ADR is required.
- Normalisation of IP literals has its own boundary tests for every encoding listed above. Known bypass forms are added as test cases when discovered.
- Roadmap item R6 is narrowed accordingly (no DNS verification).
