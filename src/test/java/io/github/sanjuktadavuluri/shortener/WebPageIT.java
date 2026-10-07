package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Issue #6: shortening a link from the web page, with no JavaScript (plain HTML form post). */
class WebPageIT extends IntegrationTest {

  @Test
  void theHomePageOffersOneLabelledFieldAndOneButton() {
    MvcTestResult page = mvc.get().uri("/").exchange();

    assertThat(page).hasStatus(200).hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
    Document html = html(page);
    Element form = html.selectFirst("form[method=post][action=/]");
    assertThat(form).isNotNull();
    assertThat(form.select("input[name=url]")).hasSize(1);
    assertThat(form.select("button[type=submit]")).hasSize(1);
    Element input = form.selectFirst("input[name=url]");
    assertThat(html.select("label[for=" + input.id() + "]").text()).isNotBlank();
  }

  @Test
  void submittingTheFormShowsTheShortUrl() {
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult page = submit("https://example.com/very/long");

    assertThat(page).hasStatus(200);
    Element shortUrl = html(page).selectFirst("[data-short-url]");
    assertThat(shortUrl).isNotNull();
    assertThat(shortUrl.text()).isEqualTo("http://sho.rt/Ab3xK9q");
    assertThat(shortUrl.attr("href")).isEqualTo("http://sho.rt/Ab3xK9q");
    assertThat(mvc.get().uri("/Ab3xK9q")).hasHeader("Location", "https://example.com/very/long");
  }

  @Test
  void aRejectedLongUrlShowsItsReasonAtTheFieldAndKeepsWhatWasTyped() {
    MvcTestResult page = submit("ftp://example.com/file");

    assertThat(page).hasStatus(422);
    Document html = html(page);
    Element input = html.selectFirst("input[name=url]");
    assertThat(input.val()).isEqualTo("ftp://example.com/file");
    assertThat(input.attr("aria-invalid")).isEqualTo("true");
    Element reason = html.getElementById(input.attr("aria-describedby"));
    assertThat(reason).isNotNull();
    assertThat(reason.text())
        .isEqualTo("Only http:// and https:// web addresses can be shortened.");
    assertThat(html.select("[data-short-url]")).isEmpty();
  }

  @Test
  void thePageAppliesTheSameRulesAsTheApi() {
    MvcTestResult page = submit(BASE_URL + "/Ab3xK9q");

    assertThat(page).hasStatus(422);
    assertThat(html(page).select("[role=alert]").text())
        .isEqualTo("Links to this shortener aren't allowed.");
  }

  @Test
  void whenNoFreeShortCodeIsFoundThePageSaysSo() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/first");
    shortCodes.willReturn("Ab3xK9q", "Ab3xK9q", "Ab3xK9q", "Ab3xK9q", "Ab3xK9q");

    MvcTestResult page = submit("https://example.com/second");

    assertThat(page).hasStatus(503);
    assertThat(html(page).select("[role=alert]").text())
        .isEqualTo("Couldn't find a free Short Code. Please try again.");
  }

  private MvcTestResult submit(String longUrl) {
    return mvc.post()
        .uri("/")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("url", longUrl)
        .exchange();
  }

  private static Document html(MvcTestResult page) {
    return Jsoup.parse(
        new String(page.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
  }
}
