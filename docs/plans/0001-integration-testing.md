---
status: active
date: 2026-10-07
owner: SanjuktaDavuluri
spec: 0001
---

# Plan 0001: Integration testing

How we prove that the parts of the shortener work **together**, across real boundaries (HTTP, Spring wiring, SQLite, Flyway, and later containers and PostgreSQL), as the system grows from v1 through the production-readiness and reliability releases. This plan is tracked: each phase has a status, an exit criterion, and the issues that deliver it.

## 1. Definitions

| Level | What it exercises | Runs with | Naming | Speed target |
|---|---|---|---|---|
| **Unit** | One unit in isolation: a Rule, the Rule Set, the Short Code generator | Maven **Surefire** (`test` phase) | `*Test` | milliseconds |
| **Integration** | The real application wired by Spring, over real infrastructure: HTTP layer, the create-Link service, Link Store, SQLite file, Flyway migrations | Maven **Failsafe** (`integration-test` / `verify` phases) | `*IT` | < 30 s for the whole suite in Release 1 |
| **System / container** (Release 2+) | The packaged artifact or Docker image, over a real network socket | Failsafe + Testcontainers | `*SystemIT` | minutes |
| **Browser** (since #8) | The running app in real Chrome: JavaScript behaviour, accessibility wiring, Lighthouse | Playwright + Lighthouse in `e2e/`, CI job **Browser checks** | `e2e/*.js` | ~1 min |
| **Performance & resilience** (Release 3) | Load and failure behaviour | Separate tooling (R13, R14), not part of `verify` | — | — |

`./mvnw verify` runs **unit and integration** tests, locally and in CI. A PR cannot merge unless both pass.

## 2. Principles

1. **Test at the agreed seams** (spec 0001): the HTTP surface and the Rule Set. Integration tests never call internal classes directly and never assert on SQL or table contents. Behaviour is observed through HTTP responses only, with one declared exception: **the Click Store, until R2** (spec 0003, seam 3; amended by #52). There is no HTTP read path for Clicks until R2's stats API, so integration tests observe stored Clicks through the Click Store's *list the Clicks for a Short Code* operation, after calling `flush()` on the Click Recorder instead of sleeping (ADR 0012). It is the interface R2 itself reads from, not a test-only back door, and tests still never read the `clicks` table or assert on SQL. Once R2 ships, the HTTP-level P8 assertions move to the stats API.
2. **Real infrastructure, nothing mocked inside the boundary.** Integration tests use a real SQLite file migrated by Flyway, the real Spring context and real Thymeleaf rendering. The only substitutions are the **`ShortCodeGenerator`** bean, which is replaced with a scripted one so Collisions are deterministic, and (since #52) the **`Clock`**, which is replaced with a fixed one the test can move forward, so each Click's time is known in advance. Since #54, a stand-in **`ClickRecorder`** (one that blocks or throws) and a stand-in **`ClickStore`** (one that holds a batch's write transaction open, saving through the real Click Store) are swapped in, each only on its own `TestApps` instance and database file, never in the shared context, to prove the Redirect never waits for or fails because of Click recording.
3. **Isolation.** Integration test classes share Spring's cached application context, and with it one SQLite file, one scripted generator and one clock. **Before every test** the base class resets them: the Click Recorder is flushed first (so no Click from the previous test is written into the next test's database), Flyway rebuilds the schema from the migrations (`clean` + `migrate`, enabled only in tests), the Short Code script is emptied and the clock is set back to its fixed time. Tests may therefore reuse Short Codes and run in any order, and every test also re-proves that the migrations apply to an empty database. (A per-class `@TempDir` doesn't work with the context cache: JUnit deletes the directory while the cached context still uses it.)
4. **Independent expectations.** Expected values come from the spec (status codes, headers, literal Short Codes from the scripted generator), never recomputed the way the code computes them.
5. **Every acceptance criterion is traceable.** Each integration test names the issue and the acceptance criterion it covers (see the traceability matrix, section 5).

## 3. Environments and fixtures

| Concern | Approach |
|---|---|
| Application context | `@SpringBootTest` + `@AutoConfigureMockMvc`, driven with `MockMvcTester` (in-process; no network) |
| Real HTTP (one smoke class) | `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a real HTTP client, to prove the servlet container sends 302s, `Location` and `Cache-Control` exactly as specified |
| Database | One SQLite file per Spring test context under `target/test-databases/` (removed by `./mvnw clean`); reset to an empty, freshly migrated schema before every test |
| Short Codes | `@TestConfiguration` that supplies a scripted `ShortCodeGenerator` (e.g. `AAAAAAA`, `AAAAAAA`, `BBBBBBB`) |
| Configuration | `BASE_URL` set to a fixed test value (e.g. `http://sho.rt`) so Short URLs are predictable |
| Restart / production config | `TestApps.start(...)` starts an extra real instance (settings as command-line arguments, so they outrank `application.properties`), e.g. on the **same** database file for persistence checks |
| Requests | Shared helpers in the base class (`postLink`, `postBody`, `createLink`); request bodies are serialised by Jackson |
| Clicks (since #52) | A `@TestConfiguration` supplies a fixed `TestClock` (reset to `IntegrationTest.NOW`, moved forward with `advance`); `storedClicks(shortCode)` flushes the Click Recorder and lists through the Click Store; `TestApps.startWithEnvironment` starts an instance as if extra environment variables were set |
| Stand-ins and concurrency (since #54) | `TestApps.startWithConfigurations` adds `@TestConfiguration` classes (a `@Primary` stand-in) to one instance; `TestApps.port` gives its real port for an HTTP client. Threads are coordinated with latches and bounded futures (`succeedsWithin`), and `ScriptedShortCodeGenerator.awaitDraw` signals that a Link creation on another thread has drawn its Short Code, never with fixed sleeps |
| CI | GitHub Actions, Temurin JDK 25, `./mvnw verify`; Failsafe reports uploaded as build artifacts when a test fails |

## 4. Phases

| Phase | Release | Scope | Exit criterion | Delivered by | Status |
|---|---|---|---|---|---|
| **P1: Walking skeleton** | 1 | Create → Redirect → 404 over the full context; persistence across a restart; Flyway migrates an empty database; one real-HTTP smoke test; production configuration (`BASE_URL` default) | All #3 acceptance criteria covered by `*IT` tests; green in CI | #3 | done ([#12](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/12)) |
| **P2: Collisions** | 1 | Scripted Collision then success; all 5 attempts collide → `503` | #4 criteria covered | #4 | done ([#18](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/18)) |
| **P3: Rules through HTTP** | 1 | Each Rejection Reason surfaces as `422` via `POST /links`; malformed JSON → `422`. (Rule edge cases stay as **unit** tests.) | #5 criteria covered | #5 | done ([#13](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/13)) |
| **P4: Web page** | 1 | Full page renders; no-JS form post shows the Short URL; rejection shown inline with input preserved; HTMX request (`HX-Request: true`) returns only the fragment; both render the same fragment | #6 and #7 criteria covered | #6, #7, #8 | done ([#19](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/19), [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20), #8) |
| **P5: Container smoke** | 2 | Build the Docker image; start it with Testcontainers; health endpoint, create and Redirect over a real socket | Image-level `*SystemIT` green in CI | R11, R12 | planned |
| **P6: Link Store contract suite** | 3 | One abstract contract test suite, run against **SQLite and PostgreSQL** (Testcontainers), proving both implementations behave identically, including duplicate Short Codes | Both implementations pass the same suite | R5 | planned |
| **P7: Failure behaviour** | 3 | Database unavailable or slow; behaviour of health checks and error responses; recovery after restart | Documented, tested failure modes; incident write-ups for anything found | R14 | planned |
| **P8: Clickstream** | 2 | A successful `GET` Redirect stores exactly one Click; a `404`, a non-Short-Code path, a `HEAD` request, Link creation and the web page store none; the V2 migration applies to empty and Release 1 databases. Observed through the Click Store seam until R2 (principle 1) | Spec 0003 criteria covered (#50–#52 and the remaining spec 0003 tickets) | R10 | in progress (#52, #54) |

**Status values:** `planned` → `not started` → `in progress` → `done` (link the PR).

## 5. Traceability matrix (Release 1)

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
| #8 | Favicon, `robots.txt` and stylesheet served; non-Short-Code paths no longer hit the Short Code route | `PageAssetsIT` (5 tests) | ☑ #8 |
| #8 | Status region visually hidden but announced | `PageAssetsIT` → `theStatusRegionIsVisuallyHiddenButStillAnnounced` | ☑ #8 |
| #8 | **Browser checks in CI:** no reload, copy + clipboard, announcements, inline 422, focus, no JS errors, no 404s, favicon, no-JS path | `e2e/browser-checks.js` (16 checks), CI job **Browser checks** | ☑ #8 |
| #8 | **Lighthouse ≥ 90** in every category, mobile and desktop | `e2e/lighthouse.js`, CI job **Browser checks** (reports uploaded) | ☑ #8 |
| #14 | A rejected Long URL creates no Link and uses no Short Code | `RejectionIT` → `aRejectedLongUrlCreatesNoLinkAndUsesNoShortCode` | ☑ [#15](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/15) |
| #14 | Errors are `application/problem+json` with status, title, instance | `RejectionIT` → `rejectionsAreProblemDetails` | ☑ [#15](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/15) |
| #14 | Wrong `Content-Type` → `415` | `RejectionIT` → `aRequestThatIsNotLabelledAsJsonIsAnUnsupportedMediaType` | ☑ [#15](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/15) |
| #14 | Request bodies with characters that need JSON escaping reach the Rules intact | `LinkApiIT` → `aLongUrlContainingAQuoteIsRejectedNotMisreadAsBrokenJson` | ☑ [#15](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/15) |
| #16 | A malformed Long URL gets the "isn't a valid web address" Rejection Reason through the API | `LinkApiIT` → `aLongUrlContainingAQuoteIsRejectedNotMisreadAsBrokenJson` (expectation updated) | ☑ [#17](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/17) |
| #4 | A Collision is retried with the next Short Code; the existing Link is untouched | `CollisionIT` → `aCollisionIsRetriedWithTheNextShortCode` | ☑ [#18](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/18) |
| #4 | The 5th attempt can still succeed | `CollisionIT` → `theFifthAttemptCanStillSucceed` | ☑ [#18](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/18) |
| #4 | 5 Collisions → `503` problem detail; exactly 5 attempts (no 6th draw) | `CollisionIT` → `fiveCollisionsInARowFailWithAClearRetryableError` | ☑ [#18](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/18) |
| #5 | Rejected Long URL → `422` with Rejection Reason | `RejectionIT` → `aRejectedLongUrlIsRefusedWithItsRejectionReason` | ☑ [#13](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/13) |
| #5 | Self-link refused (Rule Set bound to the configured Base URL) | `RejectionIT` → `aSelfLinkIsRefused` | ☑ [#13](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/13) |
| #5 | Whitespace trimmed before Rules and storage | `RejectionIT` → `surroundingWhitespaceIsTrimmedBeforeTheRulesAndStorage` | ☑ [#13](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/13) |
| #5 | Malformed request (not JSON / no `url`) → `422` | `RejectionIT` → `aRequestThatIsNotJsonIsUnprocessable`, `aRequestWithoutAUrlIsUnprocessable` | ☑ [#13](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/13) |
| #6 | `GET /` serves a page with one labelled field and one button | `WebPageIT` → `theHomePageOffersOneLabelledFieldAndOneButton` | ☑ [#19](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/19) |
| #6 | Plain HTML form post shows the Short URL (and it Redirects) | `WebPageIT` → `submittingTheFormShowsTheShortUrl` | ☑ [#19](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/19) |
| #6 | Rejection shown at the field (`aria-invalid`, `aria-describedby`), input preserved, `422` | `WebPageIT` → `aRejectedLongUrlShowsItsReasonAtTheFieldAndKeepsWhatWasTyped` | ☑ [#19](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/19) |
| #6 | Same create-Link logic as the API (Self-link rejected identically) | `WebPageIT` → `thePageAppliesTheSameRulesAsTheApi` | ☑ [#19](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/19) |
| #6 | No free Short Code → `503` page with the message | `WebPageIT` → `whenNoFreeShortCodeIsFoundThePageSaysSo` | ☑ [#19](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/19) |
| #7 | Self-hosted HTMX loaded; form posts via `hx-post`, targets and swaps `#shortener` | `WebPageHtmxIT` → `thePageLoadsSelfHostedHtmxAndTheFormPostsThroughIt` | ☑ [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20) |
| #7 | HTMX request → only the fragment | `WebPageHtmxIT` → `anHtmxRequestReceivesOnlyTheShortenerFragment` | ☑ [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20) |
| #7 | Fragment and full page render identical markup | `WebPageHtmxIT` → `theFragmentAndTheFullPageRenderTheSameMarkup` | ☑ [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20) |
| #7 | Rejection returns as a fragment with the reason at the field | `WebPageHtmxIT` → `aRejectionComesBackAsAFragmentWithTheReasonAtTheField` | ☑ [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20) |
| #7 | HTMX configured to swap 422 and 503 | `WebPageHtmxIT` → `htmxIsConfiguredToSwapRejectionsAndFailures` | ☑ [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20) |
| #7 | Copy button present but hidden without JS | `WebPageHtmxIT` → `theShortUrlHasACopyButtonThatStaysHiddenWithoutJavaScript` | ☑ [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20) |
| #7 | Persistent status region outside the fragment | `WebPageHtmxIT` → `thePageHasAStatusRegionOutsideTheFragmentForAnnouncements` | ☑ [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20) |
| #7 | **In a real browser:** no reload, copy + "Copied ✓", clipboard, announcement, inline 422, focus to field, no JS errors; no-JS path works | Manual Playwright + Chrome run recorded in PR for #7 (14/14); **automated in CI by #8** | ☑ [#20](https://github.com/SanjuktaDavuluri/simple-url-shortener/pull/20) (manual); automated in CI by #8 |
| #50 | Every pooled connection runs in WAL mode with a 5000 ms busy timeout; the pool holds 4 connections (ADR 0021) | `SqliteConcurrencyIT` → `thePoolHoldsFourConnectionsEachInWalModeWithAFiveSecondBusyTimeout` | ☑ #50 |
| #50 | A Release 1 database (rollback journal, with Links) comes up in WAL mode and still Redirects its Links | `SqliteConcurrencyIT` → `aRelease1DatabaseComesUpInWalModeAndStillRedirectsItsLinks` | ☑ #50 |
| #50 | The existing suite, including the Flyway clean + migrate reset, passes unchanged with WAL on | Whole `./mvnw verify` suite (every `*IT` resets through `IntegrationTest`) | ☑ #50 |
| #51 | **Unit (spec 0003 seam 2):** Referrer Host is the lower-case host without port, user info, path or query | `ClickClassifierTest` → `theReferrerHostIsTheLowerCaseHostWithoutPortUserInfoPathOrQuery` | ☐ #51 |
| #51 | **Unit:** a missing, malformed or non-`http(s)` Referer (e.g. `android-app:`, `ftp:`) gives no Referrer Host | `ClickClassifierTest` → `aMissingRefererGivesNoReferrerHost`, `aMalformedRefererGivesNoReferrerHost`, `aRefererThatIsNotAnHttpOrHttpsUrlGivesNoReferrerHost` | ☐ #51 |
| #51 | **Unit:** Googlebot, Bingbot, Slackbot, Twitterbot, facebookexternalhit, WhatsApp and Discordbot are bots | `ClickClassifierTest` → `knownCrawlersAndLinkPreviewFetchersAreBotsOnDesktop` | ☐ #51 |
| #51 | **Unit:** the bot pattern list is kept in one place, matched case-insensitively, with its own tests | `BotPatternsTest` (4 tests) | ☐ #51 |
| #51 | **Unit:** a crawler whose user agent starts with `Mozilla/` is still a bot (bot before browser) | `ClickClassifierTest` → `aBotWhoseUserAgentStartsWithMozillaIsStillABot` | ☐ #51 |
| #51 | **Unit:** Chrome and Firefox on desktop are browser/desktop; Safari on iPhone and Chrome on Android are browser/mobile | `ClickClassifierTest` → `desktopBrowsersAreBrowserAgentsOnDesktop`, `mobileBrowsersAreBrowserAgentsOnMobile`, `theDeviceClassIsMobileOnlyWithAMobileMarker` | ☐ #51 |
| #51 | **Unit:** `curl`, `wget`, `python-requests` and an empty or missing user agent are other/desktop | `ClickClassifierTest` → `toolsAndAMissingUserAgentAreOtherAgentsOnDesktop` | ☐ #51 |
| #52 | **Click Store seam (spec 0003 seam 3):** a successful `GET` Redirect stores exactly one Click with the Short Code, the fixed clock's time, the Referrer Host, Agent Category and Device Class, observed after `flush()`, never by sleeping | `ClickstreamIT` → `aRedirectStoresExactlyOneClickWithItsShortCodeTimeReferrerHostAndCategories` | ☐ #52 |
| #52 | A `404`, a path that can't be a Short Code, a `HEAD` request, Link creation (`POST /links` and the web page form) and `GET /` store no Click | `ClickstreamIT` → `anUnknownShortCodeStoresNoClick`, `aPathThatCannotBeAShortCodeStoresNoClick`, `aHeadRequestStoresNoClick`, `creatingLinksAndViewingTheWebPageStoreNoClick` | ☐ #52 |
| #52 | N Redirects of one Link store N Clicks, oldest first; Clicks for two Links stay separate | `ClickstreamIT` → `nRedirectsOfOneLinkStoreNClicksOldestFirstAndEachLinkKeepsItsOwn` | ☐ #52 |
| #52 | The full `Referer` URL and the raw `User-Agent` appear nowhere in the stored Click | `ClickstreamIT` → `theFullRefererAndTheRawUserAgentAppearNowhereInTheStoredClick` | ☐ #52 |
| #52 | The V2 migration applies to an empty database (the per-test reset) and, through `TestApps`, to a Release 1 database whose Links keep Redirecting | Every `*IT` extending `IntegrationTest` (reset); `ClickstreamIT` → `theClicksMigrationAppliesToARelease1DatabaseWhoseLinksKeepRedirecting` | ☐ #52 |
| #52 | The existing Redirect tests pass unchanged: same `302`, `Location` and `Cache-Control: no-store` | `LinkApiIT` → `followingAShortUrlRedirectsToItsLongUrl`; `RedirectOverHttpIT` → `aRealHttpClientReceivesTheRedirectAsSpecified` (both untouched) | ☐ #52 |
| #52 | `CLICK_QUEUE_CAPACITY`, `CLICK_BATCH_SIZE` and `CLICK_FLUSH_INTERVAL` bind, with defaults `10000`, `500` and `1s` | `ClickSettingsIT` → `theClickSettingsDefaultToTenThousandQueuedFiveHundredPerBatchAndOneSecond`, `theClickSettingsBindFromTheirEnvironmentVariables` | ☐ #52 |
| #52 | **Unit (story 13):** one background writer saves Clicks in batches no larger than the batch size, one Click Store call per batch; a full queue drops and counts; `record` never throws; `stats()` reports recorded, dropped and pending | `QueuedClickRecorderTest` (5 tests, stand-in Click Store) | ☐ #52 |
| #52 | No SQL touching `clicks` exists outside `JdbcClickStore`; no test asserts on SQL or reads the table | Review check: the only `clicks` SQL is in `JdbcClickStore` and `V2__create_clicks.sql`; tests read Clicks only through `IntegrationTest.storedClicks` (the Click Store) | ☐ #52 |
| #52 | Plan 0001: principle 1 amended for the Click Store seam, P8 moved to Release 2 and in progress, these rows added | This plan (sections 2, 4 and 5) | ☐ #52 |
| #53 | **Unit (spec 0003 seam 4, stories 3, 14, 15):** with a blocked stand-in Click Store and a small capacity, Clicks beyond capacity are dropped and counted, and `record` returns without waiting | `QueuedClickRecorderTest` → `withTheStoreBlockedClicksBeyondTheQueueCapacityAreDroppedAndCountedWithoutWaiting` | ☐ #53 |
| #53 | **Unit (story 16):** a batch that fails to save counts all its Clicks as dropped, logs a single warning with the count, and the next batch is saved and counted as recorded (no retry) | `QueuedClickRecorderTest` → `aBatchThatFailsToSaveIsCountedAsDroppedWarnedOnceAndTheNextBatchIsSaved` | ☐ #53 |
| #53 | **Unit (story 13):** with more queued Clicks than the batch size, no save call receives more than batch-size Clicks | `QueuedClickRecorderTest` → `noSaveCallReceivesMoreClicksThanTheBatchSize` | ☐ #53 |
| #53 | **Unit (story 19):** `stats()` reports the expected recorded, dropped and pending numbers in each scenario above | The four tests above and below assert `stats()` before and after the store is released, a batch fails or a flush | ☐ #53 |
| #53 | **Unit (story 17):** repeated drops within one flush interval produce at most one warning line carrying the accumulated count (timed by a hand-moved ticker); drops held back are warned on stop; the writer logs its start and stop; captured log output has no Short Code, Referrer Host, user agent, Long URL or stack trace, even when the store's failure message carries them | `QueuedClickRecorderTest` → `dropsWithinOneFlushIntervalProduceAtMostOneWarningCarryingTheAccumulatedCount`, `dropsHeldBackWithinTheIntervalAreWarnedWhenTheWriterStops`, `theWriterLogsItsStartAndStop`, `theLogCarriesCountsOnlyNeverAShortCodeReferrerUserAgentOrLongUrl` | ☐ #53 |
| #53 | **Unit (story 3):** `record` never throws, even if the queue fails or refuses a Click or the clock fails or runs backwards; each such Click is counted as dropped exactly once | `QueuedClickRecorderTest` → `recordingNeverThrows`, `recordingNeverThrowsWhenTheQueueFails`, `aClickTheQueueRefusesIsDroppedAndCounted`, `recordingNeverThrowsWhenTheClockFailsAndEachDropIsCountedOnce`, `recordingNeverThrowsWhenTheClockRunsBackwards` | ☐ #53 |
| #53 | Plan 0001: these rows added | This plan (section 5) | ☐ #53 |
| #54 | **Redirect never waits (story 2):** with a stand-in Click Recorder that blocks indefinitely (swapped in by a `@TestConfiguration` on a `TestApps` instance), a real HTTP client receives `GET /{short_code}`'s `302` with `Location` and `Cache-Control: no-store` within a short bound, while the recorder still holds the Click | `RedirectNeverWaitsForClicksIT` → `aClickRecorderThatBlocksDoesNotHoldBackTheRedirect` | ☐ #54 |
| #54 | **Redirect never fails (story 3):** with a stand-in Click Recorder that throws a `RuntimeException`, the Redirect still returns the same `302` | `RedirectNeverWaitsForClicksIT` → `aClickRecorderThatThrowsDoesNotBreakTheRedirect` | ☐ #54 |
| #54 | **Concurrency (story 22, ADR 0021):** while a stand-in Click Store holds a Click batch's write transaction open, a Redirect of an existing Link completes with its `302` before the transaction is released | `SqliteConcurrencyIT` → `aRedirectCompletesWhileAClickBatchHoldsTheWriteLock` (red with a pool of 1) | ☐ #54 |
| #54 | **Concurrency (ADR 0021):** while the Click writer holds the write lock for less than the busy timeout, `POST /links` waits inside SQLite and succeeds with `201` once the lock is released | `SqliteConcurrencyIT` → `linkCreationWaitsForTheClickWritersLockAndSucceedsOnceItIsReleased` (red with `busy_timeout=0` or a pool of 1) | ☐ #54 |
| #54 | No assertion on SQL or table contents and no fixed sleeps: threads are coordinated with latches, futures with bounds, and the scripted generator's draw signal | Review check: `RedirectNeverWaitsForClicksIT`, `SqliteConcurrencyIT` (stand-ins write only through the real Click Store) | ☐ #54 |
| #54 | Plan 0001: these rows added; the stand-in Click Recorder and Click Store recorded as substitutions (principle 2, section 3) | This plan (sections 2, 3 and 5) | ☐ #54 |

Rows are filled in (☐ → ☑ with the PR link) as each ticket's PR merges.

## 6. Entry and exit criteria

- **Entry (per ticket):** the ticket's acceptance criteria are listed in this plan's matrix before implementation starts.
- **Exit (per ticket):** every matrix row for the ticket is ☑, `./mvnw verify` is green in CI, and the PR description links the tests.
- **Exit (plan, for Release 1):** P1–P4 done. The plan stays `active` across later releases and is marked `done` only when P5–P8 have been delivered or explicitly dropped (with a reason recorded here).

## 7. Risks

| Risk | Mitigation |
|---|---|
| A slow Spring context makes the suite sluggish | Reuse the context across test classes (Spring's test context cache); keep scripted-generator configuration identical between classes |
| Shared context state (database, generator) leaks between tests | Reset before every test (Flyway clean + migrate, empty script); proven by a mutation check that disabling the reset breaks the suite (#14) |
| SQLite file locking between parallel tests | One database file per Spring test context; integration tests run sequentially in Release 1 |
| `MockMvcTester` hides servlet-container behaviour (headers, redirects) | The single real-HTTP smoke test (`RedirectOverHttpIT`) |
| JVM tests can't execute the page's JavaScript (HTMX swaps, copy button, focus, announcements) | `e2e/` browser checks in real Chrome, run in CI (job **Browser checks**) on every PR since #8 |
| Behaviour differs between SQLite and PostgreSQL | Contract suite in P6 before the R5 migration ships |

## 8. Tracking

- Tracked by GitHub issue [#10](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/10) (label `plan`). Each phase's status is updated here in the PR that changes it.
- Reviewed at the end of each release.
