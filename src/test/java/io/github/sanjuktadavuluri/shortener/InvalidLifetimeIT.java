package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/**
 * Issue #98 (spec 0004, stories 4 and 5): through the HTTP seam, an {@code expires_in_days} that
 * isn't a JSON integer from 1 to 365 is refused with the Lifetime's validation message and creates
 * no Link. It is never coerced. The request shape is checked first, then the Lifetime, then the
 * Rule Set.
 */
class InvalidLifetimeIT extends IntegrationTest {

  private static final String INVALID_LIFETIME =
      "expires_in_days must be a whole number of days from 1 to 365.";

  private static final String MALFORMED =
      "The request body must be JSON like {\"url\": \"https://example.com\"}.";

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "366", "1.5", "\"30\"", "true", "{}"})
  void anInvalidLifetimeIsRefusedWithTheValidationMessage(String expiresInDays) {
    assertThat(
            post(
                "{\"url\": \"https://example.com/event\", \"expires_in_days\": %s}", expiresInDays))
        .hasStatus(422)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": 422, "title": "Unprocessable Content", "instance": "/links",
             "detail": "%s"}
            """
                .formatted(INVALID_LIFETIME));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "366", "1.5", "\"30\"", "true", "{}"})
  void anInvalidLifetimeCreatesNoLinkAndUsesNoShortCode(String expiresInDays) {
    shortCodes.willReturn("Ab3xK9q");

    assertThat(
            post(
                "{\"url\": \"https://example.com/event\", \"expires_in_days\": %s}", expiresInDays))
        .hasStatus(422);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(404);

    assertThat(postLink("https://example.com/accepted"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.short_code")
        .isEqualTo("Ab3xK9q");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"expires_in_days\": 0}",
        "{\"url\": null, \"expires_in_days\": \"30\"}",
        "{\"url\": {\"href\": \"https://example.com\"}, \"expires_in_days\": 1.5}",
        "{\"url\": [\"https://example.com\"], \"expires_in_days\": 366}",
        "{\"expires_in_days\": 1.5, \"url\": true}",
      })
  void aMalformedUrlIsReportedBeforeAnInvalidLifetime(String body) {
    assertThat(postBody(body, MediaType.APPLICATION_JSON))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo(MALFORMED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "30", "365", "null"})
  void aValidLifetimeKeepsTheRejectionReasonOfARuleBreakingUrl(String expiresInDays) {
    assertThat(
            post("{\"url\": \"ftp://example.com/file\", \"expires_in_days\": %s}", expiresInDays))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Only http:// and https:// web addresses can be shortened.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1.5", "\"30\""})
  void theLifetimeIsCheckedBeforeTheRuleSet(String expiresInDays) {
    assertThat(
            post("{\"url\": \"ftp://example.com/file\", \"expires_in_days\": %s}", expiresInDays))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo(INVALID_LIFETIME);
  }

  private org.springframework.test.web.servlet.assertj.MvcTestResult post(
      String template, String expiresInDays) {
    return postBody(template.formatted(expiresInDays), MediaType.APPLICATION_JSON);
  }
}
