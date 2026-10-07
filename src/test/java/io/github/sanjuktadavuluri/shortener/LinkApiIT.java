package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Issue #3: creating a Link and following it, through the HTTP seam. */
class LinkApiIT extends IntegrationTest {

  @Test
  void creatingALinkReturnsItsShortUrl() {
    shortCodes.willReturn("Ab3xK9q");

    assertThat(
            mvc.post()
                .uri("/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"https://example.com/very/long\"}"))
        .hasStatus(201)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "short_code": "Ab3xK9q",
              "short_url": "http://sho.rt/Ab3xK9q",
              "long_url": "https://example.com/very/long"
            }
            """);
  }

  @Test
  void followingAShortUrlRedirectsToItsLongUrl() {
    shortCodes.willReturn("Rd1rect");
    createLink("https://example.com/very/long");

    assertThat(mvc.get().uri("/Rd1rect"))
        .hasStatus(302)
        .hasHeader("Location", "https://example.com/very/long")
        .hasHeader("Cache-Control", "no-store");
  }

  @Test
  void anUnknownShortCodeIsNotFound() {
    assertThat(mvc.get().uri("/Nope123")).hasStatus(404);
  }

  @Test
  void shorteningTheSameLongUrlTwiceCreatesTwoLinks() {
    shortCodes.willReturn("Tw1ceAa", "Tw1ceBb");
    createLink("https://example.com/same");
    createLink("https://example.com/same");

    assertThat(mvc.get().uri("/Tw1ceAa")).hasHeader("Location", "https://example.com/same");
    assertThat(mvc.get().uri("/Tw1ceBb")).hasHeader("Location", "https://example.com/same");
  }

  @Test
  void shortCodesAreCaseSensitive() {
    shortCodes.willReturn("CaSe1Ab", "case1ab");
    createLink("https://example.com/upper");
    createLink("https://example.com/lower");

    assertThat(mvc.get().uri("/CaSe1Ab")).hasHeader("Location", "https://example.com/upper");
    assertThat(mvc.get().uri("/case1ab")).hasHeader("Location", "https://example.com/lower");
  }

  private void createLink(String longUrl) {
    assertThat(
            mvc.post()
                .uri("/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"" + longUrl + "\"}"))
        .hasStatus(201);
  }
}
