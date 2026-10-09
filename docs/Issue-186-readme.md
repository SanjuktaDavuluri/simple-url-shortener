# Issue #186: Document Redirect and Stats operations with Bearer scheme and single 404

## What users and operators see

- **Redirect operation:** The OpenAPI definition documents `GET /{short_code}` with all three possible outcomes:
  - `302` with `Location` header and `Cache-Control: no-store` for a valid Short Code before its Expiry
  - `404` for an unknown Short Code
  - `410 Gone` for an Expired Link (spec 0004, ADR 0022)

- **Stats operation:** The OpenAPI definition documents `GET /links/{short_code}/stats` with:
  - `200` response body containing the full stats shape: `short_code`, `short_url`, `long_url`, `created_at`, `generated_at`, `clicks`, `bot_clicks`, `last_click_at`, `clicks_per_day`, `by_agent_category`, `by_device_class`, `top_referrer_hosts`, `no_referrer_host`
  - Single `404` response documenting the identical failure case for: unknown Short Code, missing or malformed `Authorization` header, empty token, wrong token, and Links without a Manage Token hash
  - No suggestion of different reasons for the `404`, preventing disclosure of which Short Codes exist

- **Bearer token security:** The definition declares an HTTP Bearer security scheme and marks it as required on the Stats operation only. Examples show the Manage Token is a 43-character base64url string, never in a URL, and returned once from the create response.

- **No real values in examples:** Short Codes, tokens, URLs, referrer hosts and user agents in the OpenAPI definition's examples are obviously fake (e.g. the reserved `example.com`), safe to publish.

## What reviewers and maintainers see

- **Specification completeness:** The definition now covers all three public JSON API operations (POST /links, GET /{short_code}, GET /links/{short_code}/stats), with all documented status codes, response bodies, headers and security requirements. Reviewers can check the contract without reading prose specs.

- **Drift detection extended:** The existing `OpenApiDefinitionIT` test verifies:
  - The definition contains exactly three operations (no accidental web page, static assets or actuator paths)
  - Redirect documents `302`, `404` and `410` (including the Expiry check from spec 0004)
  - Stats documents exactly one `404` with descriptions covering all failure cases
  - Bearer scheme is declared once and required on Stats only
  - No example carries a real token shape, referrer host or user agent

- **Annotations on controllers:** The Redirect and Stats operations gain OpenAPI annotations (`@Operation`, `@ApiResponse`) describing their contracts. The shared `404` response is declared once and reused on the Stats operation.

- **Existing tests unchanged:** All Redirect and Stats integration tests pass without modification, proving the definition matches the actual behaviour.
