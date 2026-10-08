package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @Test
  void theFormOffersAnOptionalLabelledLifetimeFieldAndLeavesValidationToTheServer() {
    assertLifetimeFieldIsOffered(html(mvc.get().uri("/").exchange()));
  }

  @Test
  void aBlankLifetimeCreatesALinkThatNeverExpiresAndShowsNoExpiry() {
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult page = submit("https://example.com/very/long", "");

    assertThat(page).hasStatus(200);
    Document html = html(page);
    assertThat(html.selectFirst("[data-short-url]").text()).isEqualTo("http://sho.rt/Ab3xK9q");
    assertThat(html.select("[data-expiry]")).isEmpty();
    assertThat(html.getElementById("result").text()).doesNotContain("Expires");
    clock.advance(Duration.ofDays(3650));
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);
  }

  @Test
  void aLifetimeShowsTheExpiryInUtcUnderTheShortUrl() {
    clock.set(Instant.parse("2026-10-08T10:15:00Z"));
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult page = submit("https://example.com/very/long", "30");

    assertThat(page).hasStatus(200);
    Document html = html(page);
    assertThat(html.selectFirst("[data-short-url]").text()).isEqualTo("http://sho.rt/Ab3xK9q");
    assertThat(html.selectFirst("#result [data-expiry]").text())
        .isEqualTo("Expires on 2026-11-07 10:15 UTC");
    clock.set(Instant.parse("2026-11-07T10:15:00Z"));
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(410);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "366", "1.5", "abc"})
  void anInvalidLifetimeShowsTheValidationMessageAtTheFieldKeepsBothValuesAndCreatesNoLink(
      String days) {
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult page = submit("https://example.com/very/long", days);

    assertThat(page).hasStatus(422);
    assertInvalidLifetimeAtTheField(html(page), "https://example.com/very/long", days);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(404);
  }

  @Test
  void aRuleBreakingLongUrlWithABlankLifetimeStillShowsItsRejectionReasonAtTheUrlField() {
    MvcTestResult page = submit("ftp://example.com/file", "");

    assertThat(page).hasStatus(422);
    Document html = html(page);
    Element input = html.selectFirst("input[name=url]");
    assertThat(input.attr("aria-invalid")).isEqualTo("true");
    assertThat(html.getElementById(input.attr("aria-describedby")).text())
        .isEqualTo("Only http:// and https:// web addresses can be shortened.");
    assertThat(html.selectFirst("input[name=expires_in_days]").hasAttr("aria-invalid")).isFalse();
    assertThat(html.getElementById("expires_in_days-error")).isNull();
  }

  /** The Lifetime field as spec 0004 describes it; shared with {@link WebPageHtmxIT}. */
  static void assertLifetimeFieldIsOffered(Document html) {
    Element form = html.selectFirst("#shortener form");
    assertThat(form.hasAttr("novalidate")).isTrue();
    Element days = form.selectFirst("input[name=expires_in_days]");
    assertThat(days).isNotNull();
    assertThat(days.attr("type")).isEqualTo("number");
    assertThat(days.attr("min")).isEqualTo("1");
    assertThat(days.attr("max")).isEqualTo("365");
    assertThat(days.attr("inputmode")).isEqualTo("numeric");
    assertThat(days.hasAttr("required")).isFalse();
    assertThat(days.val()).isEmpty();
    assertThat(form.select("label[for=" + days.id() + "]").text())
        .isEqualTo("Expires after (days)");
    assertThat(describedBy(html, days))
        .anySatisfy(
            hint -> assertThat(hint.text()).isEqualTo("Leave blank to keep the link forever"));
  }

  /**
   * An invalid Lifetime as spec 0004 describes it: the validation message under the field, both
   * typed values kept, and no Short URL. Shared with {@link WebPageHtmxIT}.
   */
  static void assertInvalidLifetimeAtTheField(Document html, String longUrl, String days) {
    assertThat(html.selectFirst("input[name=url]").val()).isEqualTo(longUrl);
    Element input = html.selectFirst("input[name=expires_in_days]");
    assertThat(input.val()).isEqualTo(days);
    assertThat(input.attr("aria-invalid")).isEqualTo("true");
    Element message = html.getElementById("expires_in_days-error");
    assertThat(message).isNotNull();
    assertThat(message.text())
        .isEqualTo("expires_in_days must be a whole number of days from 1 to 365.");
    assertThat(message.attr("role")).isEqualTo("alert");
    assertThat(describedBy(html, input)).contains(message);
    assertThat(message.parent()).isEqualTo(input.parent());
    assertThat(message.elementSiblingIndex()).isGreaterThan(input.elementSiblingIndex());
    assertThat(html.select("[data-short-url]")).isEmpty();
  }

  /** The elements an input's {@code aria-describedby} names, in order. */
  private static List<Element> describedBy(Document html, Element input) {
    return Arrays.stream(input.attr("aria-describedby").split(" "))
        .filter(id -> !id.isBlank())
        .map(html::getElementById)
        .toList();
  }

  private MvcTestResult submit(String longUrl) {
    return mvc.post()
        .uri("/")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("url", longUrl)
        .exchange();
  }

  private MvcTestResult submit(String longUrl, String expiresInDays) {
    return mvc.post()
        .uri("/")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("url", longUrl)
        .param("expires_in_days", expiresInDays)
        .exchange();
  }

  private static Document html(MvcTestResult page) {
    return Jsoup.parse(
        new String(page.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
  }
}
