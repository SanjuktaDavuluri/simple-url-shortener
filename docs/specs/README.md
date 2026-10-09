# Specs

Specs record **what** we are building and why it matters to its users. They pair with [ADRs](../adr/), which record **why** a significant technical decision was made. Each spec is numbered, committed, and reviewed like code.

**Status lifecycle:** `draft` → `accepted` → `in-progress` → `implemented`, or `superseded by NNNN`.

**Traceability chain:** roadmap item (`docs/roadmap.md`) → spec → tickets → PRs → spec marked `implemented`.

| # | Spec | Status | Roadmap |
|---|---|---|---|
| 0001 | [v1 core: shorten and Redirect](0001-v1-core.md) | implemented | core (Release 1): tickets [#3–#8](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues) |
| 0002 | [Delivery orchestrator](0002-delivery-orchestrator.md) | implemented | R18 (Release 2): tickets [#26–#35](https://github.com/SanjuktaDavuluri/simple-url-shortener/milestone/2) |
| 0003 | [Clickstream: record a Click for every successful Redirect](0003-clickstream.md) | implemented | R10: Run R-0001, Issue #49 |
| 0004 | [Expiring Links: an optional per-Link Lifetime, answered with `410 Gone` once it has passed](0004-expiring-links.md) | implemented | R3: Run R-0002, Issue #83 |
| 0006 | [Operability basics: health checks, metrics, structured logs with request IDs, graceful shutdown and a first runbook](0006-operability-basics.md) | implemented | R12: Run R-0004, Issue #96 |
| 0005 | [Click stats per Link: the creator can see how a Link is used](0005-click-stats-per-link.md) | implemented | R2: Run R-0003, Issue #95 |
| 0007 | [Dockerize: a multi-stage image, a Compose file for a local run, and an image built and tested in CI](0007-dockerize.md) | implemented | R11: Run R-0005, Issue #171 |
| 0008 | [OpenAPI definition of the JSON API, checked in CI so it can't drift from the code](0008-openapi-definition.md) | implemented | R21: Run R-0006, Issue #184 |
