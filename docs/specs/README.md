# Specs

Specs record **what** we are building and why it matters to its users. They pair with [ADRs](../adr/), which record **why** a significant technical decision was made. Each spec is numbered, committed, and reviewed like code.

**Status lifecycle:** `draft` → `accepted` → `in-progress` → `implemented`, or `superseded by NNNN`.

**Traceability chain:** roadmap item (`docs/roadmap.md`) → spec → tickets → PRs → spec marked `implemented`.

| # | Spec | Status | Roadmap |
|---|---|---|---|
| 0001 | [v1 core: shorten and Redirect](0001-v1-core.md) | implemented | core (Release 1): tickets [#3–#8](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues) |
| 0002 | [Delivery orchestrator](0002-delivery-orchestrator.md) | implemented | R18 (Release 2): tickets [#26–#35](https://github.com/SanjuktaDavuluri/simple-url-shortener/milestone/2) |
| 0003 | [Clickstream: record a Click for every successful Redirect](0003-clickstream.md) | implemented | R10: Run R-0001, Issue #49 |
