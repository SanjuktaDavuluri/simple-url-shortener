# Ticket #188: Documented OpenAPI definition

## What users and reviewers see or do

- **API client authors** can now read one machine-readable OpenAPI 3 definition at `docs/api/openapi.yaml` to understand the three public operations: creating a Link, following a Short URL, and reading a Link's stats, without consulting the prose specs.
- **Reviewers** find the definition linked from the README and onboarding guide, in one step, at a fixed path (`docs/api/openapi.yaml`).
- **Maintainers** see the regeneration command named in onboarding and in drift-test failures, so fixing drift takes one step.
- **Security reviewers** confirm the definition contains no real tokens, Short Codes, referrer hosts or user agents, making the contract file safe to publish alongside the code.

## How it's documented

- `README.md` links to `docs/api/openapi.yaml` and describes what it documents.
- `docs/onboarding.md` links to the definition and names the regeneration command (`scripts/openapi.sh` or its Maven equivalent).
- `docs/specs/README.md` includes spec 0008 (OpenAPI definition) in the index with its status and link.
- `docs/roadmap.md` R21 lists spec 0008 and its tickets (#185 generation, #186 operation details, #187 drift test, #188 documentation).
- `docs/plans/0001-integration-testing.md` gains rows #185–#187 tracing the drift test, content checks and operation-specific assertions back to spec 0008's user stories and decisions.
