package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Issue #5: the Rule Set guards Link creation through the HTTP seam. */
class RejectionIT extends IntegrationTest {

  @Test
  void aRejectedLongUrlIsRefusedWithItsRejectionReason() {
    assertThat(postLink("{\"url\": \"ftp://example.com/file\"}"))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Only http:// and https:// web addresses can be shortened.");
  }

  @Test
  void aSelfLinkIsRefused() {
    assertThat(postLink("{\"url\": \"" + BASE_URL + "/Ab3xK9q\"}"))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Links to this shortener aren't allowed.");
  }

  @Test
  void surroundingWhitespaceIsTrimmedBeforeTheRulesAndStorage() {
    shortCodes.willReturn("Tr1mmed");

    assertThat(postLink("{\"url\": \"  https://example.com/padded \\t\"}"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.long_url")
        .isEqualTo("https://example.com/padded");
    assertThat(mvc.get().uri("/Tr1mmed")).hasHeader("Location", "https://example.com/padded");
  }

  @Test
  void aRequestThatIsNotJsonIsUnprocessable() {
    assertThat(postLink("this is not json"))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("The request body must be JSON like {\"url\": \"https://example.com\"}.");
  }

  @Test
  void aRequestWithoutAUrlIsUnprocessable() {
    assertThat(postLink("{}"))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("The request body must be JSON like {\"url\": \"https://example.com\"}.");
  }

  private MvcTestResult postLink(String body) {
    return mvc.post()
        .uri("/links")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }
}
