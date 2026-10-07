package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Issue #8: the page's static assets, and paths that can't be Short Codes. */
class PageAssetsIT extends IntegrationTest {

  @Test
  void theFaviconIsServedInsteadOfFallingIntoTheShortCodeRoute() {
    assertThat(mvc.get().uri("/favicon.ico")).hasStatus(200);
    assertThat(mvc.get().uri("/favicon.svg"))
        .hasStatus(200)
        .hasContentTypeCompatibleWith("image/svg+xml");
    assertThat(page().selectFirst("link[rel=icon][href=/favicon.svg]")).isNotNull();
  }

  @Test
  void robotsTxtIsServed() {
    assertThat(mvc.get().uri("/robots.txt"))
        .hasStatus(200)
        .hasContentTypeCompatibleWith(MediaType.TEXT_PLAIN);
  }

  @Test
  void theStylesheetIsLinkedAndServed() {
    assertThat(page().selectFirst("link[rel=stylesheet][href=/css/app.css]")).isNotNull();
    assertThat(mvc.get().uri("/css/app.css"))
        .hasStatus(200)
        .hasContentTypeCompatibleWith("text/css");
  }

  @Test
  void theStatusRegionIsVisuallyHiddenButStillAnnounced() {
    assertThat(page().getElementById("status").hasClass("visually-hidden")).isTrue();
    assertThat(mvc.get().uri("/css/app.css")).bodyText().contains(".visually-hidden");
  }

  @Test
  void aPathThatCannotBeAShortCodeIsNotFound() {
    assertThat(mvc.get().uri("/not-a-short-code")).hasStatus(404);
    assertThat(mvc.get().uri("/abc")).hasStatus(404);
  }

  private Document page() {
    return Jsoup.parse(
        new String(
            mvc.get().uri("/").exchange().getResponse().getContentAsByteArray(),
            StandardCharsets.UTF_8));
  }
}
