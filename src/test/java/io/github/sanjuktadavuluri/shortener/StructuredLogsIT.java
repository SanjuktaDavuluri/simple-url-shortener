package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * Issue #119 (spec 0006 stories 19–20 and 23–27, Testing Decisions seam 3): the console log is one
 * JSON object per line, one access line per public request, and never a personal trail. Captured
 * log output is a declared seam (plan 0001 principle 1); lines are parsed as JSON and asserted on
 * by field, never by message text.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogsIT extends IntegrationTest {

  static final String DISTINCTIVE_URL = "https://example.com/zq-distinctive-path-81734?tk=zq81734";
  static final String REFERER = "https://referrer-zq81734.example.net/from/zq-page?src=zq81734";
  static final String USER_AGENT = "ZqAgent-81734/9.9 (zq-platform)";
  static final String AUTHORIZATION = "Bearer zq-secret-81734";
  static final String QUERY = "utm=zq81734&email=zq81734%40example.com";

  @Test
  void everyConsoleLineIsExactlyOneJsonObjectWithTimestampLevelLoggerAndMessage(
      CapturedOutput out) {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/very/long");
    mvc.get().uri("/Ab3xK9q").exchange();

    List<JsonNode> lines = LogLines.parseAll(out);

    assertThat(lines).isNotEmpty();
    assertThat(lines)
        .allSatisfy(
            line -> {
              assertThat(LogLines.timestamp(line)).isNotBlank();
              assertThat(LogLines.level(line)).isNotBlank();
              assertThat(LogLines.logger(line)).isNotBlank();
              assertThat(LogLines.text(line, "message")).isNotNull();
            });
  }

  @Test
  void aRedirectProducesOneAccessLineWithItsRouteStatusDurationAndRequestId(CapturedOutput out) {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/very/long");

    MvcTestResult redirect = mvc.get().uri("/Ab3xK9q?a=b").exchange();

    String requestId = redirect.getResponse().getHeader("X-Request-Id");
    List<JsonNode> access = LogLines.accessLines(out, "GET", "/{short_code}");
    assertThat(access).hasSize(1);
    JsonNode line = access.get(0);
    assertThat(LogLines.text(line, "http_method")).isEqualTo("GET");
    assertThat(LogLines.text(line, "route")).isEqualTo("/{short_code}");
    assertThat(line.get("status").asInt()).isEqualTo(302);
    assertThat(line.get("duration_ms").isNumber()).isTrue();
    assertThat(LogLines.text(line, "request_id")).isEqualTo(requestId);
    assertThat(line.toString()).doesNotContain("Ab3xK9q").doesNotContain("a=b");
  }

  @Test
  void aPathNoHandlerMatchesIsLoggedAsUnmatchedWithoutItsRawPath(CapturedOutput out) {
    mvc.get().uri("/no/such-path-zq81734/deeper").exchange();

    List<JsonNode> access = LogLines.accessLines(out, "GET", "unmatched");
    assertThat(access).hasSize(1);
    assertThat(access.get(0).get("status").asInt()).isEqualTo(404);
    assertThat(out.getAll()).doesNotContain("zq81734");
  }

  @Test
  void nothingPersonalReachesTheLog(CapturedOutput out) {
    shortCodes.willReturn("Ab3xK9q");
    assertThat(postLink(DISTINCTIVE_URL)).hasStatus(201);
    mvc.get()
        .uri("/Ab3xK9q?" + QUERY)
        .header("Referer", REFERER)
        .header("User-Agent", USER_AGENT)
        .header("Authorization", AUTHORIZATION)
        .header("X-Forwarded-For", "203.0.113.77")
        .exchange();
    assertThat(postLink("ftp://zq81734.example.com/file")).hasStatus(422);
    assertThat(postBody("{\"url\": zq81734", MediaType.APPLICATION_JSON)).hasStatus(422);
    flushClicks();

    assertThat(out.getAll())
        .doesNotContain("zq-distinctive")
        .doesNotContain("zq81734")
        .doesNotContain("ZqAgent")
        .doesNotContain("zq-secret")
        .doesNotContain("203.0.113.77")
        .doesNotContain("utm=");
  }

  @Test
  void clientErrorsProduceNoErrorLine(CapturedOutput out) {
    assertThat(postLink("ftp://example.com/file")).hasStatus(422);
    assertThat(postBody("not json", MediaType.APPLICATION_JSON)).hasStatus(422);
    assertThat(mvc.get().uri("/Zz9yX8w")).hasStatus(404);

    assertThat(LogLines.parseAll(out))
        .noneSatisfy(line -> assertThat(LogLines.level(line)).isEqualTo("ERROR"));
  }

  @Test
  void anUnsafeIncomingRequestIdIsNeverLogged(CapturedOutput out) {
    mvc.get().uri("/").header("X-Request-Id", "evil\"zq81734{\"level\":\"ERROR\"}").exchange();
    mvc.get().uri("/").header("X-Request-Id", "zq81734" + "a".repeat(70)).exchange();

    assertThat(out.getAll()).doesNotContain("zq81734");
    assertThat(LogLines.parseAll(out)).isNotEmpty();
  }

  @Test
  void aSafeIncomingRequestIdIsOnTheAccessLine(CapturedOutput out) {
    mvc.get().uri("/").header("X-Request-Id", "abc-123_DEF.4").exchange();

    Optional<JsonNode> line = LogLines.accessLines(out, "GET", "/").stream().findFirst();
    assertThat(line).isPresent();
    assertThat(LogLines.text(line.get(), "request_id")).isEqualTo("abc-123_DEF.4");
  }
}
