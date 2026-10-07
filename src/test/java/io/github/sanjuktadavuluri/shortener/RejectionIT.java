package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Issue #5: the Rule Set guards Link creation through the HTTP seam. */
class RejectionIT extends IntegrationTest {

  private static final String MALFORMED =
      "The request body must be JSON like {\"url\": \"https://example.com\"}.";

  @Test
  void aRejectedLongUrlIsRefusedWithItsRejectionReason() {
    assertThat(postLink("ftp://example.com/file"))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Only http:// and https:// web addresses can be shortened.");
  }

  @Test
  void aSelfLinkIsRefused() {
    assertThat(postLink(BASE_URL + "/Ab3xK9q"))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Links to this shortener aren't allowed.");
  }

  @Test
  void aRejectedLongUrlCreatesNoLinkAndUsesNoShortCode() {
    shortCodes.willReturn("Ab3xK9q");

    assertThat(postLink("ftp://example.com/file")).hasStatus(422);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(404);

    assertThat(postLink("https://example.com/accepted"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.short_code")
        .isEqualTo("Ab3xK9q");
  }

  @Test
  void rejectionsAreProblemDetails() {
    assertThat(postLink("ftp://example.com/file"))
        .hasStatus(422)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": 422, "title": "Unprocessable Content", "instance": "/links"}
            """);
  }

  @Test
  void surroundingWhitespaceIsTrimmedBeforeTheRulesAndStorage() {
    shortCodes.willReturn("Ab3xK9q");

    assertThat(postLink("  https://example.com/padded \t"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.long_url")
        .isEqualTo("https://example.com/padded");
    assertThat(mvc.get().uri("/Ab3xK9q")).hasHeader("Location", "https://example.com/padded");
  }

  @Test
  void aRequestThatIsNotJsonIsUnprocessable() {
    assertThat(postBody("this is not json", MediaType.APPLICATION_JSON))
        .hasStatus(422)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo(MALFORMED);
  }

  @Test
  void aRequestWithoutAUrlIsUnprocessable() {
    assertThat(postBody("{}", MediaType.APPLICATION_JSON))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo(MALFORMED);
  }

  @Test
  void aRequestThatIsNotLabelledAsJsonIsAnUnsupportedMediaType() {
    assertThat(postBody("url=https://example.com", MediaType.APPLICATION_FORM_URLENCODED))
        .hasStatus(415);
  }
}
