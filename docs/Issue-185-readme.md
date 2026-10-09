# Issue #185: OpenAPI definition for create Link, with regeneration command

## What users and operators see

- **OpenAPI contract file:** `docs/api/openapi.yaml` is a machine-readable OpenAPI 3 definition of the JSON API. It describes:
  - `POST /links`: create a Link, optionally with a Lifetime; includes request body shape and response fields (`short_code`, `short_url`, `long_url`, `manage_token`, `expires_at`)
  - `GET /{short_code}`: Redirect to the Long URL or answer `410` for an Expired Link
  - `GET /links/{short_code}/stats`: read Link stats with Bearer token authentication

- **Status codes and responses:** The definition documents:
  - Create: `201` on success, `400` for malformed JSON, `422` for rejected URL or invalid Lifetime, `503` when no free Short Code
  - Redirect: `302` before Expiry, `410` once expired, `404` for unknown Short Code
  - Stats: `200` on success with stats body, `404` for unknown Short Code, wrong token, or no token (all identical to prevent leaking which Short Code exists)

- **Security:** The Bearer token security scheme is documented. The Manage Token is:
  - Returned only in the `201` create response (shown once; unsafe to lose it)
  - Sent in the `Authorization: Bearer <token>` header to read Stats
  - Never recoverable if lost

- **No public documentation endpoint:** The running service exposes no `/v3/api-docs` endpoint or interactive documentation UI on the public port. The definition is a reviewed, committed file, not generated at runtime.

## What reviewers and maintainers see

- **Drift detection:** Every pull request runs a test (`OpenApiDefinitionIT`) that regenerates the definition from the code and fails if it differs from the committed file. This prevents the contract from silently drifting when fields, status codes or security rules change.

- **Regeneration command:** When the test fails, the error message tells you how to regenerate the definition: `scripts/openapi.sh` or an equivalent Maven profile (set in implementation). One command makes the file match the code.

- **Code-first:** The definition is generated from annotations on the controllers and response records, so the code is the single source of truth. Annotations are reviewed like code.

- **Checked in CI:** The drift test runs in `./mvnw verify`, covered by the existing required check **"Verify (format, compile, analysis, unit + integration tests)"**. No new CI job is added or required-check name changed.
