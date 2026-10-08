# Changelog

All notable changes to the shortener are recorded here. The project follows [Semantic Versioning](https://semver.org/), and each numbered **Release** on the [roadmap](docs/roadmap.md) ends with a tagged version and [release notes](docs/releases/).

## [Unreleased]

### Added (documentation only)
- Releases 2 and 3 designed: ADRs 0007–0019, a re-prioritised roadmap, a reviewer's guide in the README, and an [onboarding guide](docs/onboarding.md) (#23).
- An [engineering summary](docs/summary.md): architecture, the orchestration model, three scenarios (greenfield, brownfield, ambiguous), testing, risks, assumptions and limitations, linked from the README (#72).
- `scripts/local.sh`, which runs the app in the background for manual testing (#22).

## [1.0.0] - 2026-10-07

Release 1, greenfield v1: [spec 0001](docs/specs/0001-v1-core.md) implemented. Release notes: [docs/releases/v1.0.0.md](docs/releases/v1.0.0.md).

### Added
- `POST /links`: create a Link from a Long URL. It returns `201` with `short_code`, `short_url` and `long_url` (#3).
- `GET /{shortCode}`: `302 Found` Redirect with `Cache-Control: no-store`, or `404` (#3).
- URL Rules: well-formed, `http`/`https` only, host present, at most 2048 characters, and no Self-links. Refusals get `422` problem details with a Rejection Reason (#5, #16).
- Collision handling: up to 5 Short Code draws, then `503` (#4).
- The web page: works without JavaScript (#6), is enhanced with HTMX (no reload, inline errors, copy button) (#7), and has a polished, accessible design in light and dark mode with Lighthouse 100 in every category (#8).
- CI on every PR: **Verify** (format, compile, Error Prone, unit and integration tests) and **Browser checks** (Playwright and Lighthouse), both required on `main` (#3, #8).

[Unreleased]: https://github.com/SanjuktaDavuluri/simple-url-shortener/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/SanjuktaDavuluri/simple-url-shortener/releases/tag/v1.0.0
