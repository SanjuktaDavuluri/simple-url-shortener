---
status: accepted
date: 2026-10-07
---

# A strict, enforced Content-Security-Policy and standard security headers

The web page (ADR 0006) self-hosts every script (HTMX and `copy.js`), uses no inline scripts or styles, and makes no third-party requests. That makes a **strict Content-Security-Policy** cheap to adopt now and expensive to retrofit later. We enforce one from day one, with the standard companion headers.

## Decision

Every HTML and API response from the application port carries:

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'` |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | Camera, microphone, geolocation, payment, USB and similar features disabled |
| `Strict-Transport-Security` | Only when the service is configured as served over HTTPS |

- **Redirect (`302`) responses** keep the browser's default referrer behaviour, so destination sites still see where a Click came from. The policy applies to our own pages.
- HTMX's injected indicator `<style>` is switched off (`includeIndicatorStyles: false` in `htmx-config`). Indicator styling lives in `app.css`.
- Headers are added by **one small servlet filter**. There is no Spring Security filter chain: the only authorisation in the product is the manage token (ADR 0014).
- **Verified twice:** integration tests assert the headers, and the browser checks fail on **any** CSP violation reported by Chrome.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Strict, enforced CSP + standard headers via a small filter** | Strong XSS and framing protection at almost no cost while the page is small; no new dependencies; proven by tests in the browser | Constrains all future front-end work (see Consequences) | **Chosen** |
| B | Same headers through Spring Security | The standard header DSL | Brings the whole security filter chain, which locks all endpoints by default and needs careful disabling, for one feature | Rejected |
| C | Report-only CSP first | Can't break anything | Protects nothing; meant for large legacy sites where violations are unknown. Ours is small and fully known | Rejected |
| D | A permissive CSP (`'unsafe-inline'`, CDNs allowed) | Easy future front-end changes | Gives up most of the XSS protection | Rejected |

**In short:** while the page is small and fully self-hosted, lock it down completely, and make anyone loosening it later justify why.

## Consequences

- **Front-end rules from now on:** no inline `<script>` or `style=""`/`<style>`; no inline event handlers (`onclick`); no third-party scripts, fonts, styles or images. New assets are self-hosted and versioned (like `static/js/VENDORED.md`).
- **Do not loosen the policy** (`'unsafe-inline'`, external hosts) without a new ADR.
- The browser checks' "no CSP violations" assertion is the guard. A violation fails CI, not just a console warning.
