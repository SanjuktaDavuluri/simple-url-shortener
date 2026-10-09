package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issues #105 and #107 (spec 0005 seam 1; stories 8, 10–17, 19–24; ADRs 0013, 0014 and 0023): a
 * Link's Stats over the JSON API, read with its Manage Token in an {@code Authorization: Bearer}
 * header. Clicks come from real Redirects with chosen headers, saved by flushing the Click Recorder
 * (never by sleeping), with the clock fixed so the 30-day window is known. Every failure is the
 * same {@code 404}, byte for byte.
 */
@ExtendWith(OutputCaptureExtension.class)
class LinkStatsIT extends IntegrationTest {

  /** Scripted Manage Tokens: plainly fake, but shaped like real ones (43 characters). */
  static final String FIRST = "first-scripted-manage-token-for-tests-00001";

  static final String SECOND = "second-scripted-manage-token-for-tests-0002";

  /** The SHA-256 of each, worked out with {@code shasum -a 256}, not by the code under test. */
  private static final String FIRST_HASH =
      "2f6da021345588db6a010080f5a0b8745ce5054716c32f712a923563d76a623d";

  private static final String SECOND_HASH =
      "018f15577a6280a676334871dd70a5e8986398e7fc2bb0d35e24c4affa2b780f";

  /** When the Stats are read: the fixed clock, moved on from the Clicks. */
  private static final Instant READ_AT = Instant.parse("2026-10-08T12:00:00.123Z");

  private static final String CHROME_ON_ANDROID =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/126.0.0.0 Mobile Safari/537.36";

  private static final String FIREFOX_ON_LINUX =
      "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0";

  private static final String CURL = "curl/8.5.0";

  private static final String GOOGLEBOT =
      "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

  private static final String GOOGLEBOT_SMARTPHONE =
      "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 (KHTML, like"
          + " Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 (compatible; Googlebot/2.1;"
          + " +http://www.google.com/bot.html)";

  /** The 29 days of the window before today when every Click was today: all zero. */
  private static final String NO_CLICKS_9_SEPTEMBER_TO_7_OCTOBER =
      """
      {"date": "2026-09-09", "clicks": 0}, {"date": "2026-09-10", "clicks": 0},
      {"date": "2026-09-11", "clicks": 0}, {"date": "2026-09-12", "clicks": 0},
      {"date": "2026-09-13", "clicks": 0}, {"date": "2026-09-14", "clicks": 0},
      {"date": "2026-09-15", "clicks": 0}, {"date": "2026-09-16", "clicks": 0},
      {"date": "2026-09-17", "clicks": 0}, {"date": "2026-09-18", "clicks": 0},
      {"date": "2026-09-19", "clicks": 0}, {"date": "2026-09-20", "clicks": 0},
      {"date": "2026-09-21", "clicks": 0}, {"date": "2026-09-22", "clicks": 0},
      {"date": "2026-09-23", "clicks": 0}, {"date": "2026-09-24", "clicks": 0},
      {"date": "2026-09-25", "clicks": 0}, {"date": "2026-09-26", "clicks": 0},
      {"date": "2026-09-27", "clicks": 0}, {"date": "2026-09-28", "clicks": 0},
      {"date": "2026-09-29", "clicks": 0}, {"date": "2026-09-30", "clicks": 0},
      {"date": "2026-10-01", "clicks": 0}, {"date": "2026-10-02", "clicks": 0},
      {"date": "2026-10-03", "clicks": 0}, {"date": "2026-10-04", "clicks": 0},
      {"date": "2026-10-05", "clicks": 0}, {"date": "2026-10-06", "clicks": 0},
      {"date": "2026-10-07", "clicks": 0},""";

  private static final String REFERER =
      "https://reader:secret@News.Example.com:8443/private/draft?ref=feed#notes";

  /** The one body every failure gets (spec 0005, API contract). */
  private static final String NOT_FOUND_BODY =
      """
      {
        "type": "about:blank",
        "title": "Not Found",
        "status": 404,
        "detail": "No stats found for this Short Code."
      }
      """;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void theRightTokenGetsTheHeadlineStatsWithBotsKeptOutOfTheHeadlineClickCount() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    createLink("https://example.com/very/long");

    redirect("Ab3xK9q", REFERER, CHROME_ON_ANDROID);
    clock.advance(Duration.ofSeconds(1));
    redirect("Ab3xK9q", null, FIREFOX_ON_LINUX);
    clock.advance(Duration.ofMillis(1004));
    redirect("Ab3xK9q", REFERER, CURL);
    clock.advance(Duration.ofSeconds(5));
    redirect("Ab3xK9q", REFERER, GOOGLEBOT);
    flushClicks();
    clock.set(READ_AT);

    MvcTestResult stats = stats("Ab3xK9q", "Bearer " + FIRST);

    assertThat(stats)
        .hasStatus(200)
        .hasContentTypeCompatibleWith("application/json")
        .hasHeader("Cache-Control", "no-store")
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "short_code": "Ab3xK9q",
              "short_url": "http://sho.rt/Ab3xK9q",
              "long_url": "https://example.com/very/long",
              "created_at": "%s",
              "generated_at": "2026-10-08T12:00:00.123Z",
              "clicks": 3,
              "bot_clicks": 1,
              "last_click_at": "2026-10-08T09:30:02.004Z",
              "clicks_per_day": [
                %s
                {"date": "2026-10-08", "clicks": 3}
              ],
              "by_agent_category": {"browser": 2, "other": 1, "bot": 1},
              "by_device_class": {"desktop": 2, "mobile": 1},
              "top_referrer_hosts": [{"host": "news.example.com", "clicks": 2}],
              "no_referrer_host": 1
            }
            """
                .formatted(createdAtOf(stats), NO_CLICKS_9_SEPTEMBER_TO_7_OCTOBER));
  }

  @Test
  void aClick29DaysAgoIsInTheClicksPerDayAndOne30DaysAgoIsOutButStillInTheHeadlineCount() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    clock.set(Instant.parse("2026-09-08T09:30:00Z"));
    createLink("https://example.com/older");

    redirect("Ab3xK9q", null, FIREFOX_ON_LINUX); // 30 days ago: out of the series
    clock.set(Instant.parse("2026-09-08T23:59:59.999Z"));
    redirect("Ab3xK9q", null, FIREFOX_ON_LINUX); // the last moment before the window
    clock.set(Instant.parse("2026-09-09T09:30:00Z"));
    redirect("Ab3xK9q", null, FIREFOX_ON_LINUX); // 29 days ago: the first day of the series
    clock.set(Instant.parse("2026-10-07T23:59:59.999Z"));
    redirect("Ab3xK9q", null, CURL); // just before UTC midnight: yesterday
    clock.set(NOW);
    redirect("Ab3xK9q", null, CHROME_ON_ANDROID); // today
    flushClicks();

    MvcTestResult stats = stats("Ab3xK9q", "Bearer " + FIRST);

    assertThat(body(stats).get("clicks").asLong()).isEqualTo(5);
    assertThat(clicksPerDay(stats))
        .containsExactly(
            "2026-09-09: 1",
            "2026-09-10: 0",
            "2026-09-11: 0",
            "2026-09-12: 0",
            "2026-09-13: 0",
            "2026-09-14: 0",
            "2026-09-15: 0",
            "2026-09-16: 0",
            "2026-09-17: 0",
            "2026-09-18: 0",
            "2026-09-19: 0",
            "2026-09-20: 0",
            "2026-09-21: 0",
            "2026-09-22: 0",
            "2026-09-23: 0",
            "2026-09-24: 0",
            "2026-09-25: 0",
            "2026-09-26: 0",
            "2026-09-27: 0",
            "2026-09-28: 0",
            "2026-09-29: 0",
            "2026-09-30: 0",
            "2026-10-01: 0",
            "2026-10-02: 0",
            "2026-10-03: 0",
            "2026-10-04: 0",
            "2026-10-05: 0",
            "2026-10-06: 0",
            "2026-10-07: 1",
            "2026-10-08: 1");
  }

  @Test
  void theBreakdownsAccountForEveryNonBotClickAndBotClicksOnlyForBotClicksAndTheirCategory() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    createLink("https://example.com/very/long");

    redirect("Ab3xK9q", REFERER, CHROME_ON_ANDROID);
    redirect("Ab3xK9q", "https://t.co/xyz", CHROME_ON_ANDROID);
    redirect("Ab3xK9q", REFERER, FIREFOX_ON_LINUX);
    redirect("Ab3xK9q", null, FIREFOX_ON_LINUX);
    redirect("Ab3xK9q", null, CURL);
    redirect("Ab3xK9q", "https://crawler.example/seen", GOOGLEBOT);
    redirect("Ab3xK9q", "https://crawler.example/seen", GOOGLEBOT_SMARTPHONE);
    flushClicks();

    MvcTestResult stats = stats("Ab3xK9q", "Bearer " + FIRST);

    assertThat(stats)
        .hasStatus(200)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "clicks": 5,
              "bot_clicks": 2,
              "by_agent_category": {"browser": 4, "other": 1, "bot": 2},
              "by_device_class": {"desktop": 3, "mobile": 2},
              "no_referrer_host": 2
            }
            """);
    assertThat(topReferrerHosts(stats)).containsExactly("news.example.com: 2", "t.co: 1");
    assertThat(clicksPerDay(stats)).hasSize(30).last().isEqualTo("2026-10-08: 5");
    JsonNode body = body(stats);
    long clicks = body.get("clicks").asLong();
    assertThat(
            body.get("by_device_class").get("desktop").asLong()
                + body.get("by_device_class").get("mobile").asLong())
        .as("the Device Class split sums to the Headline Click count")
        .isEqualTo(clicks);
    assertThat(
            body.get("top_referrer_hosts").get(0).get("clicks").asLong()
                + body.get("top_referrer_hosts").get(1).get("clicks").asLong()
                + body.get("no_referrer_host").asLong())
        .as("the top Referrer Hosts and no_referrer_host account for every non-bot Click")
        .isEqualTo(clicks);
    assertThat(stats).body().asString().doesNotContain("crawler.example");
  }

  @Test
  void theTopReferrerHostsAreCutAtTenByClicksThenByHost() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    createLink("https://example.com/very/long");

    redirect("Ab3xK9q", "https://zz.example/a", FIREFOX_ON_LINUX);
    redirect("Ab3xK9q", "https://zz.example/b", CHROME_ON_ANDROID);
    for (String host :
        List.of(
            "k.example",
            "j.example",
            "i.example",
            "h.example",
            "g.example",
            "f.example",
            "e.example",
            "d.example",
            "c.example",
            "b.example",
            "a.example")) {
      redirect("Ab3xK9q", "https://" + host + "/", FIREFOX_ON_LINUX);
    }
    flushClicks();

    MvcTestResult stats = stats("Ab3xK9q", "Bearer " + FIRST);

    assertThat(body(stats).get("clicks").asLong()).isEqualTo(13);
    assertThat(topReferrerHosts(stats))
        .containsExactly(
            "zz.example: 2",
            "a.example: 1",
            "b.example: 1",
            "c.example: 1",
            "d.example: 1",
            "e.example: 1",
            "f.example: 1",
            "g.example: 1",
            "h.example: 1",
            "i.example: 1");
    assertThat(body(stats).get("no_referrer_host").asLong()).isZero();
  }

  @Test
  void theCreationTimeIsIsoUtcToTheMillisecond() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    createLink("https://example.com/very/long");

    assertThat(createdAtOf(stats("Ab3xK9q", "Bearer " + FIRST)))
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
  }

  @Test
  void aLinkWithNoClicksGetsZerosThirtyZeroDaysAndNoReferrerHostsNotAnError() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    createLink("https://example.com/quiet");

    MvcTestResult stats = stats("Ab3xK9q", "Bearer " + FIRST);

    assertThat(stats)
        .hasStatus(200)
        .hasHeader("Cache-Control", "no-store")
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "generated_at": "2026-10-08T09:30:00.000Z",
              "clicks": 0,
              "bot_clicks": 0,
              "last_click_at": null,
              "by_agent_category": {"browser": 0, "other": 0, "bot": 0},
              "by_device_class": {"desktop": 0, "mobile": 0},
              "top_referrer_hosts": [],
              "no_referrer_host": 0
            }
            """);
    assertThat(clicksPerDay(stats))
        .hasSize(30)
        .allSatisfy(day -> assertThat(day).endsWith(": 0"))
        .startsWith("2026-09-09: 0")
        .endsWith("2026-10-08: 0");
  }

  @Test
  void twoLinksStatsStaySeparate() {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    manageTokens.willReturn(FIRST, SECOND);
    createLink("https://example.com/first");
    createLink("https://example.com/second");

    redirect("Ab3xK9q", null, FIREFOX_ON_LINUX);
    redirect("Ab3xK9q", null, CURL);
    clock.advance(Duration.ofSeconds(1));
    redirect("Zz9yX8w", null, GOOGLEBOT);
    flushClicks();

    assertThat(stats("Ab3xK9q", "Bearer " + FIRST))
        .hasStatus(200)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "short_code": "Ab3xK9q",
              "long_url": "https://example.com/first",
              "clicks": 2,
              "bot_clicks": 0,
              "last_click_at": "2026-10-08T09:30:00.000Z",
              "by_agent_category": {"browser": 1, "other": 1, "bot": 0}
            }
            """);
    assertThat(stats("Zz9yX8w", "Bearer " + SECOND))
        .hasStatus(200)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "short_code": "Zz9yX8w",
              "long_url": "https://example.com/second",
              "clicks": 0,
              "bot_clicks": 1,
              "last_click_at": null,
              "by_agent_category": {"browser": 0, "other": 0, "bot": 1}
            }
            """);
  }

  @Test
  void anExpiredLinksStatsAreStillReadableWithItsToken() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    assertThat(postLink("https://example.com/event", 1)).hasStatus(201);
    redirect("Ab3xK9q", null, FIREFOX_ON_LINUX);
    flushClicks();

    clock.advance(Duration.ofDays(2));
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(410);

    assertThat(stats("Ab3xK9q", "Bearer " + FIRST))
        .hasStatus(200)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"short_code": "Ab3xK9q", "clicks": 1, "last_click_at": "2026-10-08T09:30:00.000Z"}
            """);
  }

  @Test
  void anUnknownShortCodeGetsTheOne404WithItsProblemBodyAndNoStore() {
    assertThat(stats("Nope123", "Bearer " + FIRST))
        .hasStatus(404)
        .hasContentType("application/problem+json")
        .hasHeader("Cache-Control", "no-store")
        .bodyJson()
        .isStrictlyEqualTo(NOT_FOUND_BODY);
  }

  @Test
  void everyOtherFailureGetsA404ByteForByteEqualToTheUnknownShortCodeOne() {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    manageTokens.willReturn(FIRST, SECOND);
    createLink("https://example.com/first");
    createLink("https://example.com/second");
    Map<String, Object> unknown = everythingTheClientSees(stats("Nope123", "Bearer " + FIRST));

    assertThat(everythingTheClientSees(stats("Ab3xK9q", null)))
        .as("no Authorization header")
        .isEqualTo(unknown);
    assertThat(everythingTheClientSees(stats("Ab3xK9q", "Basic " + FIRST)))
        .as("a non-Bearer scheme")
        .isEqualTo(unknown);
    assertThat(everythingTheClientSees(stats("Ab3xK9q", FIRST)))
        .as("a token with no scheme")
        .isEqualTo(unknown);
    assertThat(everythingTheClientSees(stats("Ab3xK9q", "Bearer ")))
        .as("an empty token")
        .isEqualTo(unknown);
    assertThat(everythingTheClientSees(stats("Ab3xK9q", "Bearer")))
        .as("a scheme with no token")
        .isEqualTo(unknown);
    assertThat(everythingTheClientSees(stats("Ab3xK9q", "Bearer " + FIRST + "x")))
        .as("a wrong token")
        .isEqualTo(unknown);
    assertThat(everythingTheClientSees(stats("Ab3xK9q", "Bearer " + FIRST_HASH)))
        .as("the token's hash instead of the token")
        .isEqualTo(unknown);
    assertThat(everythingTheClientSees(stats("Ab3xK9q", "Bearer " + SECOND)))
        .as("another Link's token")
        .isEqualTo(unknown);
  }

  @Test
  void aLowerCaseBearerSchemeIsAccepted() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    createLink("https://example.com/very/long");

    assertThat(stats("Ab3xK9q", "bearer " + FIRST)).hasStatus(200);
    assertThat(stats("Ab3xK9q", "BEARER " + FIRST)).hasStatus(200);
  }

  @Test
  void aPathThatIsNotASevenCharacterShortCodeFallsThroughToTheNormal404() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    createLink("https://example.com/very/long");
    Map<String, Object> statsNotFound =
        everythingTheClientSees(stats("Nope123", "Bearer " + FIRST));

    for (String path :
        List.of("/links/Ab3xK9qX/stats", "/links/Ab3xK9/stats", "/links/Ab3x-9q/stats")) {
      MvcTestResult answer =
          mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + FIRST).exchange();

      assertThat(answer).as(path).hasStatus(404);
      assertThat(everythingTheClientSees(answer)).as(path).isNotEqualTo(statsNotFound);
      assertThat(answer).as(path).body().asString().doesNotContain("No stats found");
    }
  }

  @Test
  void theStatsCarryNoRawRefererOrUserAgentSent() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);
    createLink("https://example.com/very/long");
    redirect("Ab3xK9q", REFERER, CHROME_ON_ANDROID);
    redirect("Ab3xK9q", REFERER, GOOGLEBOT);
    redirect("Ab3xK9q", REFERER, CURL);
    flushClicks();

    assertThat(stats("Ab3xK9q", "Bearer " + FIRST))
        .hasStatus(200)
        .body()
        .asString()
        .doesNotContain(REFERER)
        .doesNotContain("reader")
        .doesNotContain("secret")
        .doesNotContain("private")
        .doesNotContain(CHROME_ON_ANDROID)
        .doesNotContain(GOOGLEBOT)
        .doesNotContain(CURL)
        .doesNotContain("Mozilla");
  }

  @Test
  void theTokenAndItsHashAppearInNoLogOutputOfAStatsReadOrAnyFailure(CapturedOutput output) {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    manageTokens.willReturn(FIRST, SECOND);
    createLink("https://example.com/first");
    createLink("https://example.com/second");
    redirect("Ab3xK9q", REFERER, FIREFOX_ON_LINUX);
    flushClicks();

    assertThat(stats("Ab3xK9q", "Bearer " + FIRST)).hasStatus(200);
    for (String authorization :
        new String[] {
          null, "Basic " + FIRST, FIRST, "Bearer ", "Bearer " + FIRST + "x", "Bearer " + SECOND
        }) {
      assertThat(stats("Ab3xK9q", authorization)).hasStatus(404);
    }
    assertThat(stats("Nope123", "Bearer " + FIRST)).hasStatus(404);

    assertThat(output.getAll())
        .doesNotContain(FIRST)
        .doesNotContain(FIRST_HASH)
        .doesNotContain(SECOND)
        .doesNotContain(SECOND_HASH);
  }

  /** Follows the Short URL with the given headers ({@code null} leaves one out). */
  private void redirect(String shortCode, String referer, String userAgent) {
    MockMvcTester.MockMvcRequestBuilder request = mvc.get().uri("/" + shortCode);
    if (referer != null) {
      request = request.header(HttpHeaders.REFERER, referer);
    }
    if (userAgent != null) {
      request = request.header(HttpHeaders.USER_AGENT, userAgent);
    }
    assertThat(request).hasStatus(302);
  }

  /** Reads a Link's Stats with this {@code Authorization} header ({@code null}: none at all). */
  private MvcTestResult stats(String shortCode, String authorization) {
    MockMvcTester.MockMvcRequestBuilder request = mvc.get().uri("/links/" + shortCode + "/stats");
    if (authorization != null) {
      request = request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return request.exchange();
  }

  /** A Stats body, parsed. */
  private static JsonNode body(MvcTestResult stats) {
    return JSON.readTree(stats.getResponse().getContentAsByteArray());
  }

  /** The {@code clicks_per_day} of a Stats body in the order sent, each as "date: clicks". */
  private static List<String> clicksPerDay(MvcTestResult stats) {
    return entries(body(stats).get("clicks_per_day"), "date");
  }

  /** The {@code top_referrer_hosts} of a Stats body in the order sent, each as "host: clicks". */
  private static List<String> topReferrerHosts(MvcTestResult stats) {
    return entries(body(stats).get("top_referrer_hosts"), "host");
  }

  private static List<String> entries(JsonNode array, String key) {
    List<String> entries = new ArrayList<>();
    for (int i = 0; i < array.size(); i++) {
      JsonNode entry = array.get(i);
      assertThat(entry.propertyNames()).containsExactlyInAnyOrder(key, "clicks");
      entries.add(entry.get(key).asString() + ": " + entry.get("clicks").asLong());
    }
    return entries;
  }

  /** The {@code created_at} of a Stats body, as sent. */
  private static String createdAtOf(MvcTestResult stats) {
    return JSON.readTree(stats.getResponse().getContentAsByteArray()).get("created_at").asString();
  }

  /**
   * Everything a client sees of a response: its status, every header with all its values, and the
   * body's bytes. Two answers that are equal here are indistinguishable byte for byte.
   */
  static Map<String, Object> everythingTheClientSees(MvcTestResult result) {
    MockHttpServletResponse response = result.getResponse();
    Map<String, Object> seen = new LinkedHashMap<>();
    seen.put("status", response.getStatus());
    for (String name : response.getHeaderNames()) {
      seen.put("header " + name, response.getHeaders(name));
    }
    // ISO-8859-1 maps every byte to one character, so equal strings mean equal bytes.
    seen.put("body", new String(response.getContentAsByteArray(), StandardCharsets.ISO_8859_1));
    return seen;
  }
}
