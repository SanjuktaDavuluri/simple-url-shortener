---
status: active
date: 2026-10-07
owner: SanjuktaDavuluri
spec: 0001
---

# Plan 0001: Integration testing

How we prove that the parts of the shortener work **together**, across real boundaries (HTTP, Spring wiring, SQLite, Flyway, and later containers and PostgreSQL), as the system grows from v1 through the production-readiness and reliability waves. This plan is tracked: each phase has a status, an exit criterion, and the issues that deliver it.

## 1. Definitions

| Level | What it exercises | Runs with | Naming | Speed target |
|---|---|---|---|---|
| **Unit** | One unit in isolation: a Rule, the Rule Set, the Short Code generator | Maven **Surefire** (`test` phase) | `*Test` | milliseconds |
| **Integration** | The real application wired by Spring, over real infrastructure: HTTP layer, the create-Link service, Link Store, SQLite file, Flyway migrations | Maven **Failsafe** (`integration-test` / `verify` phases) | `*IT` | < 30 s for the whole suite in wave 1 |
| **System / container** (wave 2+) | The packaged artifact or Docker image, over a real network socket | Failsafe + Testcontainers | `*SystemIT` | minutes |
| **Performance & resilience** (wave 3) | Load and failure behaviour | Separate tooling (R13, R14), not part of `verify` | — | — |

`./mvnw verify` runs **unit and integration** tests, locally and in CI. A PR cannot merge unless both pass.

## 2. Principles

1. **Test at the agreed seams** (spec 0001): the HTTP surface and the Rule Set. Integration tests never call internal classes directly and never assert on SQL or table contents. Behaviour is observed through HTTP responses only.
2. **Real infrastructure, nothing mocked inside the boundary.** Integration tests use a real SQLite file migrated by Flyway, the real Spring context and real Thymeleaf rendering. The only substitution is the **`ShortCodeGenerator`** bean, which is replaced with a scripted one so Collisions are deterministic.
3. **Isolation.** Each Spring test context gets a fresh SQLite file in a temporary directory, wired in through `@DynamicPropertySource`. Classes that share a cached context share its file, so every test uses its own distinct Short Codes and tests can run in any order. (A per-class `@TempDir` doesn't work with Spring's context cache: JUnit deletes the directory while the cached context still uses it.)
4. **Independent expectations.** Expected values come from the spec (status codes, headers, literal Short Codes from the scripted generator), never recomputed the way the code computes them.
5. **Every acceptance criterion is traceable.** Each integration test names the issue and the acceptance criterion it covers (see the traceability matrix, section 5).

## 3. Environments and fixtures

| Concern | Approach |
|---|---|
| Application context | `@SpringBootTest` + `@AutoConfigureMockMvc`, driven with `MockMvcTester` (in-process; no network) |
| Real HTTP (one smoke class) | `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a real HTTP client, to prove the servlet container sends 302s, `Location` and `Cache-Control` exactly as specified |
| Database | A fresh SQLite file in a temporary directory per Spring test context (shared by the classes that reuse that cached context; tests use distinct Short Codes); Flyway runs `V1__create_links.sql` on startup, so every run proves migrations apply to an empty database |
| Short Codes | `@TestConfiguration` that supplies a scripted `ShortCodeGenerator` (e.g. `AAAAAAA`, `AAAAAAA`, `BBBBBBB`) |
| Configuration | `BASE_URL` set to a fixed test value (e.g. `http://sho.rt`) so Short URLs are predictable |
| Restart simulation | A second application context started on the **same** database file, for persistence checks |
| CI | GitHub Actions, Temurin JDK 25, `./mvnw verify`; Failsafe reports uploaded as build artifacts when a test fails |

## 4. Phases

| Phase | Wave | Scope | Exit criterion | Delivered by | Status |
|---|---|---|---|---|---|
| **P1: Walking skeleton** | 1 | Create → Redirect → 404 over the full context; persistence across a restart; Flyway migrates an empty database; one real-HTTP smoke test; production configuration (`BASE_URL` default) | All #3 acceptance criteria covered by `*IT` tests; green in CI | #3 | done ([#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12)) |
| **P2: Collisions** | 1 | Scripted Collision then success; all 5 attempts collide → `503` | #4 criteria covered | #4 | not started |
| **P3: Rules through HTTP** | 1 | Each Rejection Reason surfaces as `422` via `POST /links`; malformed JSON → `422`. (Rule edge cases stay as **unit** tests.) | #5 criteria covered | #5 | in progress |
| **P4: Web page** | 1 | Full page renders; no-JS form post shows the Short URL; rejection shown inline with input preserved; HTMX request (`HX-Request: true`) returns only the fragment; both render the same fragment | #6 and #7 criteria covered | #6, #7 | not started |
| **P5: Container smoke** | 2 | Build the Docker image; start it with Testcontainers; health endpoint, create and Redirect over a real socket | Image-level `*SystemIT` green in CI | R11, R12 | planned |
| **P6: Link Store contract suite** | 3 | One abstract contract test suite, run against **SQLite and PostgreSQL** (Testcontainers), proving both implementations behave identically, including duplicate Short Codes | Both implementations pass the same suite | R5 | planned |
| **P7: Failure behaviour** | 3 | Database unavailable or slow; behaviour of health checks and error responses; recovery after restart | Documented, tested failure modes; incident write-ups for anything found | R14 | planned |
| **P8: Clickstream** | 4 | A successful Redirect emits exactly one Click event; a 404 emits none | Event contract covered | R10 | planned |

**Status values:** `planned` → `not started` → `in progress` → `done` (link the PR).

## 5. Traceability matrix (wave 1)

| Issue | Acceptance criterion | Integration test (class → method) | Status |
|---|---|---|---|
| #3 | `POST /links` → `201` with `short_code`, `short_url`, `long_url` | `LinkApiIT` → `creatingALinkReturnsItsShortUrl` | ☑ [#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12) |
| #3 | `GET /{code}` → `302`, `Location`, `Cache-Control: no-store` | `LinkApiIT` → `followingAShortUrlRedirectsToItsLongUrl` | ☑ [#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12) |
| #3 | Unknown Short Code → `404` | `LinkApiIT` → `anUnknownShortCodeIsNotFound` | ☑ [#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12) |
| #3 | Short Codes are case-sensitive | `LinkApiIT` → `shortCodesAreCaseSensitive` | ☑ [#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12) |
| #3 | Same Long URL twice → two Links | `LinkApiIT` → `shorteningTheSameLongUrlTwiceCreatesTwoLinks` | ☑ [#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12) |
| #3 | Links persist across restarts; the database enforces unique Short Codes | `PersistenceIT` → `linksSurviveARestart` | ☑ [#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12) |
| #3 | Production config: `BASE_URL` default | `ConfigurationIT` → `shortUrlsDefaultToLocalhostWithRealShortCodes` | ☑ [#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12) |
| #3 | Real HTTP sends the specified Redirect headers | `RedirectOverHttpIT` → `aRealHttpClientReceivesTheRedirectAsSpecified` | ☑ [#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12) |
| #4 | Collision retried; 5 Collisions → `503` | `CollisionIT` | ☐ |
| #5 | Rejected Long URL → `422` with Rejection Reason | `RejectionIT` → `aRejectedLongUrlIsRefusedWithItsRejectionReason` | ☑ #5 |
| #5 | Self-link refused (Rule Set bound to the configured Base URL) | `RejectionIT` → `aSelfLinkIsRefused` | ☑ #5 |
| #5 | Whitespace trimmed before Rules and storage | `RejectionIT` → `surroundingWhitespaceIsTrimmedBeforeTheRulesAndStorage` | ☑ #5 |
| #5 | Malformed request (not JSON / no `url`) → `422` | `RejectionIT` → `aRequestThatIsNotJsonIsUnprocessable`, `aRequestWithoutAUrlIsUnprocessable` | ☑ #5 |
| #6 | No-JS page flow, inline rejection | `WebPageIT` | ☐ |
| #7 | HTMX fragment vs full page | `WebPageHtmxIT` | ☐ |

Rows are filled in (☐ → ☑ with the PR link) as each ticket's PR merges.

## 6. Entry and exit criteria

- **Entry (per ticket):** the ticket's acceptance criteria are listed in this plan's matrix before implementation starts.
- **Exit (per ticket):** every matrix row for the ticket is ☑, `./mvnw verify` is green in CI, and the PR description links the tests.
- **Exit (plan, for wave 1):** P1–P4 done. The plan stays `active` across later waves and is marked `done` only when P5–P8 have been delivered or explicitly dropped (with a reason recorded here).

## 7. Risks

| Risk | Mitigation |
|---|---|
| A slow Spring context makes the suite sluggish | Reuse the context across test classes (Spring's test context cache); keep scripted-generator configuration identical between classes |
| SQLite file locking between parallel tests | One database file per Spring test context; integration tests run sequentially in wave 1 |
| `MockMvcTester` hides servlet-container behaviour (headers, redirects) | The single real-HTTP smoke test (`RedirectOverHttpIT`) |
| Behaviour differs between SQLite and PostgreSQL | Contract suite in P6 before the R5 migration ships |

## 8. Tracking

- Tracked by GitHub issue [#10](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/10) (label `plan`). Each phase's status is updated here in the PR that changes it.
- Reviewed at the end of each wave.
