# Ticket #187: Drift test and content checks for the OpenAPI definition

## What users and operators now see or do

- **Drift detection:** The `OpenApiDriftIT` test suite ensures the committed `docs/api/openapi.yaml` never silently diverges from what the code generates. If a change to the code alters the OpenAPI contract, `./mvnw verify` fails with a readable diff and the exact regeneration command.

- **One-step regeneration:** When the test fails, the error message names `scripts/openapi.sh`, making it possible to fix the drift in one step without a hunt for the command.

- **Content validation:** Tests verify the definition describes exactly the three public operations (`POST /links`, `GET /{short_code}`, `GET /links/{short_code}/stats`) with their documented status codes (201/400/422/503, 302/404/410, 200/404 respectively) and no actuator paths or web page routes leak in.

- **Security checks:** Examples in the definition contain no real-looking tokens, referrer hosts, or user agents—only reserved domains like `example.com`—so the contract is safe to publish.

- **Bearer scheme verification:** The Stats operation's Bearer security scheme is declared and verified to be present exactly where specified, and absent from the other operations.

- **Built into the standard check:** All drift and content checks run as part of `./mvnw verify`, covered by the existing required check "Verify (format, compile, analysis, unit + integration tests)", so no branch protection changes and no PR can merge with a drifted definition.
