# Changelog

All notable changes to the shortener are recorded here. The project follows [Semantic Versioning](https://semver.org/), and each numbered **Release** on the [roadmap](docs/roadmap.md) ends with a tagged version and [release notes](docs/releases/).

## [Unreleased]

## [2.0.0] - 2026-10-09

Release 2, orchestrated delivery, analytics and operability: specs [0002](docs/specs/0002-delivery-orchestrator.md) to [0008](docs/specs/0008-openapi-definition.md) implemented. Release notes: [docs/releases/v2.0.0.md](docs/releases/v2.0.0.md).

### Added
- The delivery orchestrator (`orchestrate`): a gated, resumable, audited path from a GitHub Issue to merge-ready PRs, with parallel Lanes, policy guardrails, Re-plan, delivery metrics and the Claude Agent SDK as its agent (R18, #26–#35). Per-step model and effort routing ([ADR 0024](docs/adr/0024-agent-model-and-effort-routed-per-step.md), #109, #110), `orchestrate waive` (#181) and a scripted demo Run (#84).
- Clickstream: every successful Redirect records a Click (Referrer Host, Agent Category, Device Class), asynchronously and with bounded, counted loss (R10, #50–#56).
- Expiring Links: `expires_in_days` on create and in the web form, `410 Gone` from the Expiry, Expired Links kept and Short Codes never reused (R3, #97–#102).
- Click stats per Link: a Manage Token returned once on create (only its SHA-256 hash is stored), `GET /links/{short_code}/stats` with a Bearer token, and a `/stats` web page (R2, #104–#107, #112, #160–#162).
- Operability: `X-Request-Id` on every response, Liveness and Readiness and Prometheus metrics on a separate management port, startup configuration validation, JSON logs, graceful shutdown, and the first [runbook](docs/runbook.md) (R12, #116–#123).
- A multi-stage, non-root container image with a `/data` volume and a Readiness health check, `compose.yaml`, and a CI job that builds and tests the image (R11, #172–#175).
- An OpenAPI definition of the JSON API, `docs/api/openapi.yaml`, generated from the code with a drift test in `./mvnw verify` (R21, #185–#188).
- An [engineering summary](docs/summary.md) (#72) and delivery metrics from the committed Event Logs (`delivery/metrics.md`).

### Changed
- `scripts/local.sh` and the CI browser-check job wait on Readiness; `stop` waits for the graceful shutdown (#122).
- `POST /links` also returns `manage_token` and `expires_at`.
- Each ticket's PR writes its notes to `docs/Issue-<n>-readme.md`, folded into the README at the Release's close-out (#149).
- The orchestrator checks a spec amendment before asking for approval and shows its diff, ignores a status-only spec edit when re-planning, clears a merged PR's approval, and checks only the hosts a command contacts (#170).

### Fixed
- Orchestrator: hardening from the first Run (#62), the board following each Lane (#58), full commit subjects at release readiness (#69), merged PRs shown as waiting (#75), conflicts found before a merge is requested (#76), shared-doc conflicts merged by row (#77), a Lane merged during a Re-plan not redone (#133), crash-safe resume (#125), a ticket publish never creating an Issue twice (#114), and close-out logging `pr_opened` with `Closes #<issue>` (#79).

## [1.0.0] - 2026-10-07

Release 1, greenfield v1: [spec 0001](docs/specs/0001-v1-core.md) implemented. Release notes: [docs/releases/v1.0.0.md](docs/releases/v1.0.0.md).

### Added
- `POST /links`: create a Link from a Long URL. It returns `201` with `short_code`, `short_url` and `long_url` (#3).
- `GET /{shortCode}`: `302 Found` Redirect with `Cache-Control: no-store`, or `404` (#3).
- URL Rules: well-formed, `http`/`https` only, host present, at most 2048 characters, and no Self-links. Refusals get `422` problem details with a Rejection Reason (#5, #16).
- Collision handling: up to 5 Short Code draws, then `503` (#4).
- The web page: works without JavaScript (#6), is enhanced with HTMX (no reload, inline errors, copy button) (#7), and has a polished, accessible design in light and dark mode with Lighthouse 100 in every category (#8).
- CI on every PR: **Verify** (format, compile, Error Prone, unit and integration tests) and **Browser checks** (Playwright and Lighthouse), both required on `main` (#3, #8).

[Unreleased]: https://github.com/SanjuktaDavuluri/simple-url-shortener/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/SanjuktaDavuluri/simple-url-shortener/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/SanjuktaDavuluri/simple-url-shortener/releases/tag/v1.0.0
