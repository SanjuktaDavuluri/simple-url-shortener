---
status: accepted
date: 2026-10-07
---

# Server-rendered web page, progressively enhanced with HTMX

The web page is rendered by FastAPI with Jinja2 templates and enhanced with **HTMX**. Submitting the form swaps in only the result fragment, so there is no full page reload. Without JavaScript the same form still works as a plain HTML POST that returns the full page. Both paths call the **same create-link logic** as the JSON API; the page is a second entry point, not a second implementation. Styling is hand-written CSS with design tokens. There is no CSS framework and no frontend build step.

This is a **backend-focused project**, but the page must still meet a current state-of-the-art bar. HTMX lets us reach that bar while keeping all behaviour on the server, where it is tested with the same tools as the API.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Jinja2 + HTMX, progressive enhancement** | A modern, no-reload feel; all logic and HTML stay server-side and testable with pytest; one ~14 KB script with no build; works without JS | One small new dependency; HTMX conventions (`hx-*` attributes, fragment endpoints) to learn | **Chosen** |
| B | **Plain Jinja2 form, full page reload** | Zero JavaScript, simplest possible | Feels dated: full reloads, and a clunky copy/feedback experience. It misses the quality bar | Rejected: too bare for the bar we set |
| C | **Static HTML + hand-written `fetch()` JavaScript** | No library; the page uses the public JSON API like any client | Rendering logic moves into untested client JS; we'd hand-roll what HTMX gives us; it breaks without JS | Rejected: more frontend code to own, against the backend focus |
| D | **SPA framework** (React, Vue, Svelte) | The richest interactivity | A build toolchain, a second language ecosystem, and a separate test stack, all for one form | Rejected as overkill |

**In short:** we chose **A over B** because B can't meet the quality bar. We chose **A over C** because HTMX keeps rendering on the server, where we test, instead of in hand-written JS. We chose **A over D** because a whole frontend stack for one form contradicts the backend focus.

## Quality bar (acceptance criteria for the spec)

1. No full page reload on submit; it still works with JavaScript disabled.
2. A rejected URL shows the rule's reason (ADR 0004) inline at the field.
3. A copy-to-clipboard button with visible confirmation; the short link is shown prominently.
4. Polished, responsive design with light and dark mode and visible focus states.
5. Accessible: labelled controls, an `aria-live` result region, full keyboard use, WCAG AA contrast.
6. Lighthouse score of 90 or above in every category, so a reviewer can reproduce the check.

## Consequences

- The server exposes HTML fragments for HTMX alongside full pages. Templates must be structured so that a fragment and the full page share one partial and never drift apart.
- The "works without JS" path must have its own test, otherwise it will silently rot.
