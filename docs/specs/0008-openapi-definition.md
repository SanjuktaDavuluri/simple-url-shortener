---
status: implemented
date: 2026-10-09
release: 2
roadmap: R21
issue: 184
triage: ready-for-agent
---

# Spec 0008: OpenAPI definition of the JSON API, checked in CI so it can't drift from the code

Glossary: `CONTEXT.md` · Decisions: ADR 0003, ADR 0007, ADR 0013, ADR 0014, ADR 0015, ADR 0022, ADR 0023 · Builds on: specs 0001, 0004, 0005, 0006 · Roadmap: **R21** in `docs/roadmap.md` · Issue: [#184](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/184)

## Problem Statement

The JSON API's contract lives only in spec prose and in tests:

- **No machine-readable contract.** A client author, a reviewer or a tool cannot read one file to learn the paths, request bodies, responses and status codes of creating a Link, following a Short URL, and reading a Link's stats.
- **Prose drifts.** The contract is spread over specs 0001, 0004 and 0005 and ADR 0014. A later change to a field name, a status or a header can leave every document stale without any check failing.
- **Easy to describe the security contract wrongly.** The Manage Token (shown once, sent as a Bearer header) and the identical `404` (the Stats endpoint never confirms which Short Codes exist) are subtle. They need one accurate, reviewed description.

## Solution

The JSON API gets an OpenAPI 3 definition that is generated from the code and committed, and CI fails when the two differ. This changes no behaviour: a mismatch that the definition exposes becomes a bug ticket, not a fix inside this work.

- **Code-first generation with springdoc** (approach decided 2026-10-07, no ADR; roadmap R21). The definition is derived from the controllers, request and response types and annotations, so the code is the source of truth.
- **A committed `docs/api/openapi.yaml`**: the generated definition, reviewable in pull requests like code.
- **A drift test** that generates the definition from the running application's code and fails when it differs from the committed file. The failure message says how to regenerate it. It runs inside `./mvnw verify`, so it is covered by the existing required check "Verify (format, compile, analysis, unit + integration tests)", and no CI job is renamed.
- **Scope of the definition: the public JSON API only**:
  1. `POST /links`: create a Link (optionally with a Lifetime, `expires_in_days`). Responses: `201` with `manage_token` shown once and `Cache-Control: no-store`; `400` malformed request; `422` rejected URL or invalid Lifetime; `503` no free Short Code.
  2. `GET /{short_code}`: the Redirect. `302` with `Location`, `404` for an unknown Short Code, `410` for an Expired Link (ADR 0022).
  3. `GET /links/{short_code}/stats`: the Stats endpoint, secured by the Manage Token as an HTTP Bearer scheme (ADR 0014). `200` with the stats body, and a single `404`.
- **Accurate description of the two subtle contracts:**
  - The Manage Token is returned only by the create response, is sent as `Authorization: Bearer <manage_token>` and never in a URL, and cannot be recovered if lost.
  - The Stats endpoint's `404` is the same for a missing or malformed header, an empty token, an unknown Short Code, a Link without a token and a wrong token. The definition documents one `404` response for all of them and does not suggest the reasons differ.
- **No real values in examples.** Short Codes, tokens and URLs in examples are obviously fake (for example the reserved `example.com` and a placeholder token). Nothing resembling a real referrer, user agent or Long URL appears (ADR 0013).
- **Out of the definition:** the web page and its form endpoints (`/`, `/stats`), static resources, and operability endpoints on the management port (spec 0006, ADR 0015). The generator is configured so they cannot leak in, and the interactive documentation UI is not exposed by the service.

## User Stories

### Reading the contract

1. As an API client author, I want one OpenAPI file that describes creating a Link, so that I can see the request body, the optional Lifetime and every response status without reading specs.
2. As an API client author, I want the Redirect described with its `302`, `404` and `410` answers and its `Location` header, so that I know what following a Short URL can return.
3. As an API client author, I want the Stats endpoint described with its full response body, so that I can parse it without guessing.
4. As an API client author, I want the Manage Token documented as an HTTP Bearer security scheme and as returned only by the create response, so that I know to keep it and how to send it.
5. As an API client author, I want the Stats `404` documented as identical for every failure, so that I don't build client logic that depends on reasons the service deliberately hides.
6. As a reviewer, I want the definition at a fixed path, `docs/api/openapi.yaml`, linked from the README and the onboarding guide, so that I can find the contract in one step.

### No drift

7. As a maintainer, I want a test that fails when the committed file differs from what the code generates, so that the contract can't silently go stale.
8. As a maintainer, I want the failure to name the regeneration command, so that fixing drift takes one step and not a hunt.
9. As a maintainer, I want regeneration to be deterministic (stable ordering, no timestamps, no host or port in the output), so that the drift test is never flaky and diffs show only real changes.
10. As a maintainer, I want the drift test to run in `./mvnw verify` and so in the existing required check, so that no branch protection changes and a drifted pull request cannot merge.
11. As a maintainer, I want the definition to be generated by the code, never edited by hand, so that the code stays the single source of truth.

### Safety

12. As a security-minded reviewer, I want the definition and its examples to contain no real tokens, Short Codes, referrers, user agents or Long URLs, so that the contract file is safe to publish (ADR 0013).
13. As an operator, I want the management port's endpoints absent from the definition, so that the private operability surface isn't advertised as public contract (spec 0006, ADR 0015).
14. As an operator, I want adding the generator to change no behaviour of the service, so that the Redirect, Stats and create responses are byte for byte what they were. No documentation UI or `/v3/api-docs` endpoint is added to the public port.
15. As a maintainer, I want any mismatch between the definition and the intended contract in specs to be filed as a bug ticket and not fixed here, so that this work stays behaviour-neutral.

## Implementation Decisions

- **Dependency:** springdoc's OpenAPI starter that matches the pinned Spring Boot 4.1.1. Check compatibility first. Its runtime endpoints stay disabled (`springdoc.api-docs.enabled` and the UI off) in the running service. The drift test enables generation in its own application instance (the `TestApps` pattern), so production behaviour is unchanged.
- **Annotations** live on the controllers and response records and describe the contract (summaries, response codes, security scheme, field descriptions). The `404` for Stats is declared once and shared. Request and response field names keep their existing snake_case JSON names.
- **Output format:** YAML, with stable key and path ordering and a pinned OpenAPI version. Servers are described generically (no real host). Serialisation is normalised so line endings and trailing newlines match.
- **Regeneration:** one documented command (a script under `scripts/` or a Maven profile; the implementation picks the smaller one and the drift failure message names it) rewrites `docs/api/openapi.yaml` from the code.
- **Drift test:** a `*IT` extending `IntegrationTest` (or a unit test over the generator if it can run without the full context), comparing generated text to the committed file exactly, with a readable diff on failure.
- **Content checks in the same suite:** the generated definition contains exactly the three public operations (no `/`, `/stats` or actuator paths), declares the Bearer scheme on the Stats operation only, lists the documented status codes, and contains no example value matching a real-looking token, referrer or user agent.
- **Documentation:** README and `docs/onboarding.md` point to the definition and the regeneration step; the integration-testing plan's matrix (`docs/plans/0001-integration-testing.md`) gains the drift and content rows; `docs/roadmap.md` R21 and the spec index are updated; `CONTEXT.md` gains a term only if a new one is introduced.

## Testing Decisions

- **Test through the public behaviour:** the generated document is the observable output. Tests compare it with the committed file and assert its structure. They do not assert on springdoc internals.
- **Drift test is the core:** it must fail when a field is renamed, a status code is added or removed, or a security requirement changes, and pass on an unchanged tree. Prove this once while building it (change the code, see it fail, regenerate, see it pass).
- **Behaviour-neutral proof:** the existing integration tests for create, Redirect and Stats pass unchanged. A new check confirms the running service on its public port exposes no API-docs or documentation UI path.
- **Prior art:** spec 0006's configuration-drift test (runbook against configuration), `IntegrationTest`/`TestApps`, and the integration-testing plan's matrix.
- Spotless and Error Prone must pass on the added annotations and test code.

## Out of Scope

- Changing the API's behaviour, status codes, field names or error bodies. Mismatches found become bug tickets.
- Serving the definition or a documentation UI from the running service.
- Generating client SDKs, contract testing against the definition from the client side, or API versioning.
- Documenting the web page and its form endpoints, and the management port's endpoints (spec 0006, ADR 0015).
- Authentication changes, rate limiting (R19) and new endpoints such as edit and delete (R4).
- Deployment of any kind, and anything that touches the maintainer's local service on port 8000 (ADR 0007).

## Further Notes

- **Decided while writing this spec, all easy to change:**
  - the definition is YAML at `docs/api/openapi.yaml`, as the Issue asks
  - the runtime documentation endpoints stay off in the service
  - the drift test lives in the existing required check and not in a new CI job
- **Possible ticket slices** for decomposition (the decompose Stage decides):
  1. Add springdoc, annotate the three operations and generate `docs/api/openapi.yaml` with the regeneration command.
  2. The drift test and content checks, including the "runtime endpoints absent" check.
  3. Documentation: README, onboarding guide, the integration-testing plan's matrix, roadmap R21 and the spec index.
- **Status lifecycle:** `draft → accepted → in-progress → implemented`. Set to `in-progress` when the first ticket starts and to `implemented` when the last ticket's work is merged. Add the spec to the index in `docs/specs/README.md` when it is accepted.
