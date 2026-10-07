---
status: accepted
date: 2026-10-07
---

# Server-rendered web page with Thymeleaf, progressively enhanced with HTMX

The web page is rendered by Spring MVC with **Thymeleaf** templates and enhanced with **HTMX**. Submitting the form swaps in only the result fragment, so there is no full page reload. Without JavaScript the same form still works as a plain HTML POST that returns the full page. Both paths call the **same create-Link logic** as the JSON API; the page is a second entry point, not a second implementation. Thymeleaf **fragments** (`th:fragment`) let the full page and the HTMX response render the *same* result partial. Styling is hand-written CSS with design tokens. There is no CSS framework and no frontend build step.

This is a **backend-focused project**, but the page must still meet a current state-of-the-art bar. HTMX lets us reach that bar while keeping all behaviour on the server, where it is tested with the same tools as the API.

## Options considered and the trade-offs

### Page approach

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Server-rendered + HTMX, progressive enhancement** | A modern, no-reload feel; all logic and HTML stay server-side and testable with `MockMvcTester`; one ~14 KB script with no build; works without JS | One small new dependency; HTMX conventions (`hx-*` attributes, fragment responses) to learn | **Chosen** |
| B | Plain server-rendered form, full page reload | Zero JavaScript, simplest possible | Feels dated: full reloads, and a clunky copy/feedback experience. It misses the quality bar | Rejected: too bare for the bar we set |
| C | Static HTML + hand-written `fetch()` JavaScript | No library; the page uses the public JSON API like any client | Rendering logic moves into untested client JS; we'd hand-roll what HTMX gives us; it breaks without JS | Rejected: more frontend code to own, against the backend focus |
| D | SPA framework (React, Vue, Svelte) | The richest interactivity | A build toolchain, a second language ecosystem, and a separate test stack, all for one form | Rejected as overkill |

### Template engine

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Thymeleaf** | Spring Boot's default; `th:fragment` maps one-to-one onto "fragment and full page share one partial"; templates are valid HTML that open in a browser; widely used with HTMX | Errors in template expressions surface at render time, not compile time | **Chosen** |
| B | JTE | Compiled, type-checked templates; faster rendering | Less common; extra build integration | Rejected: type safety isn't worth the unfamiliarity for one page |
| C | Mustache | Minimal and logic-less | Clumsier fragments; less built-in support | Rejected |

**In short:** we chose **HTMX over a plain form** because a plain form can't meet the quality bar, **over hand-written JS** because HTMX keeps rendering on the server where we test, and **over an SPA** because a whole frontend stack for one form contradicts the backend focus. We chose **Thymeleaf** because its fragments are exactly the shared-partial mechanism this design needs.

## Quality bar (acceptance criteria for the spec)

1. No full page reload on submit; it still works with JavaScript disabled.
2. A rejected URL shows the Rule's Rejection Reason (ADR 0004) inline at the field.
3. A copy-to-clipboard button with visible confirmation; the Short URL is shown prominently.
4. Polished, responsive design with light and dark mode and visible focus states.
5. Accessible: labelled controls, an `aria-live` result region, full keyboard use, WCAG AA contrast.
6. Lighthouse score of 90 or above in every category, so a reviewer can reproduce the check.

## Consequences

- The server returns HTML fragments for HTMX requests (detected by the `HX-Request` header) alongside full pages. The fragment and the full page must render one shared Thymeleaf fragment and never drift apart.
- The "works without JS" path must have its own test, otherwise it will silently rot.
