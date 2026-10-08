package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Issue #3: creating a Link and following it, through the HTTP seam. */
class LinkApiIT extends IntegrationTest {

  @Test
  void creatingALinkReturnsItsShortUrl() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn("first-scripted-manage-token-for-tests-00001");

    assertThat(postLink("https://example.com/very/long"))
        .hasStatus(201)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "short_code": "Ab3xK9q",
              "short_url": "http://sho.rt/Ab3xK9q",
              "long_url": "https://example.com/very/long",
              "manage_token": "first-scripted-manage-token-for-tests-00001"
            }
            """);
  }

  @Test
  void followingAShortUrlRedirectsToItsLongUrl() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/very/long");

    assertThat(mvc.get().uri("/Ab3xK9q"))
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
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    createLink("https://example.com/same");
    createLink("https://example.com/same");

    assertThat(mvc.get().uri("/Ab3xK9q")).hasHeader("Location", "https://example.com/same");
    assertThat(mvc.get().uri("/Zz9yX8w")).hasHeader("Location", "https://example.com/same");
  }

  @Test
  void shortCodesAreCaseSensitive() {
    shortCodes.willReturn("Ab3xK9q", "ab3xk9q");
    createLink("https://example.com/upper");
    createLink("https://example.com/lower");

    assertThat(mvc.get().uri("/Ab3xK9q")).hasHeader("Location", "https://example.com/upper");
    assertThat(mvc.get().uri("/ab3xk9q")).hasHeader("Location", "https://example.com/lower");
  }

  @Test
  void aLongUrlContainingAQuoteIsRejectedNotMisreadAsBrokenJson() {
    assertThat(postLink("https://example.com/search?q=\"quoted\""))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo(
            "That isn't a valid web address. Check it for spaces or characters like"
                + " \" < > { } |.");
  }
}
