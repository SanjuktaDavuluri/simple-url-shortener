package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Issue #161 (spec 0005 stories 7, 19–24; ADRs 0014 and 0023): one log capture across the whole
 * life of a Manage Token, and the token never read from a query string.
 */
@ExtendWith(OutputCaptureExtension.class)
class PrivacyIT extends IntegrationTest {

  private static final String TOKEN = LinkStatsIT.FIRST;
  private static final String HASH =
      "2f6da021345588db6a010080f5a0b8745ce5054716c32f712a923563d76a623d";
  private static final String LONG_URL = "https://example.com/very/long";

  @Test
  void createStatsOkAndStats404LeaveNoTokenHashOrAuthorizationValueInTheLogs(
      CapturedOutput output) {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(TOKEN);

    assertThat(postLink(LONG_URL)).hasStatus(201).hasHeader("Cache-Control", "no-store");
    assertThat(mvc.get().uri("/Ab3xK9q").header(HttpHeaders.USER_AGENT, "curl/8.5.0"))
        .hasStatus(302);
    flushClicks();
    assertThat(
            mvc.get()
                .uri("/links/Ab3xK9q/stats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
        .hasStatus(200)
        .hasHeader("Cache-Control", "no-store");
    assertThat(
            mvc.get()
                .uri("/links/Ab3xK9q/stats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN + "x"))
        .hasStatus(404)
        .hasHeader("Cache-Control", "no-store");

    assertThat(output.getAll())
        .doesNotContain(TOKEN)
        .doesNotContain(HASH)
        .doesNotContain("Bearer ");
  }

  @Test
  void aCreateFromThePageIsNotCachedAndLogsNoToken(CapturedOutput output) {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(TOKEN);

    assertThat(
            mvc.post()
                .uri("/")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("url", LONG_URL))
        .hasStatus(200)
        .hasHeader("Cache-Control", "no-store");

    assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(HASH);
  }

  @Test
  void aTokenInTheQueryStringOfTheStatsApiIsNeverUsed() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(TOKEN);
    createLink(LONG_URL);

    var withoutToken = mvc.get().uri("/links/Ab3xK9q/stats").exchange();
    var withQuery = mvc.get().uri("/links/Ab3xK9q/stats").param("manage_token", TOKEN).exchange();

    assertThat(withQuery).hasStatus(404).hasHeader("Cache-Control", "no-store");
    assertThat(LinkStatsIT.everythingTheClientSees(withQuery))
        .isEqualTo(LinkStatsIT.everythingTheClientSees(withoutToken));
  }
}
