package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issue #7: the web page enhanced with HTMX (ADR 0006); the no-JS path is covered by WebPageIT.
 * Issue #106: the fragment carries the Manage Token panel (spec 0005, stories 3 and 7).
 */
class WebPageHtmxIT extends IntegrationTest {

  @Test
  void thePageLoadsSelfHostedHtmxAndTheFormPostsThroughIt() {
    Document page = html(mvc.get().uri("/").exchange());

    Element htmx = page.selectFirst("script[src^=/js/htmx-]");
    assertThat(htmx).isNotNull();
    assertThat(mvc.get().uri(htmx.attr("src"))).hasStatus(200);
    Element form = page.selectFirst("#shortener form");
    assertThat(form.attr("hx-post")).isEqualTo("/");
    assertThat(form.attr("hx-target")).isEqualTo("#shortener");
    assertThat(form.attr("hx-swap")).isEqualTo("outerHTML");
  }

  @Test
  void anHtmxRequestReceivesOnlyTheShortenerFragment() {
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult response = submit("https://example.com/very/long", true);

    assertThat(response).hasStatus(200);
    Document fragment = Jsoup.parseBodyFragment(body(response));
    assertThat(fragment.select("h1, title, script")).isEmpty();
    assertThat(fragment.body().children()).hasSize(1);
    assertThat(fragment.body().child(0).id()).isEqualTo("shortener");
    assertThat(fragment.selectFirst("[data-short-url]").text()).isEqualTo("http://sho.rt/Ab3xK9q");
  }

  @Test
  void anHtmxRequestReceivesOnlyTheFragmentWithTheManageTokenPanelAndIsNotCached() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(WebPageIT.SCRIPTED);

    MvcTestResult response = submit("https://example.com/very/long", true);

    assertThat(response).hasStatus(200).hasHeader("Cache-Control", "no-store");
    Document fragment = Jsoup.parseBodyFragment(body(response));
    assertThat(fragment.select("h1, title, script")).isEmpty();
    assertThat(fragment.body().children()).hasSize(1);
    assertThat(fragment.body().child(0).id()).isEqualTo("shortener");
    WebPageIT.assertManageTokenPanel(fragment, WebPageIT.SCRIPTED);
  }

  @Test
  void aRejectionComesBackAsAFragmentWithNoManageTokenPanel() {
    manageTokens.willReturn(WebPageIT.SCRIPTED);

    MvcTestResult response = submit("ftp://example.com/file", true);

    assertThat(response).hasStatus(422);
    WebPageIT.assertNoManageTokenPanel(Jsoup.parseBodyFragment(body(response)));
    assertThat(body(response)).doesNotContain(WebPageIT.SCRIPTED);
  }

  @Test
  void anInvalidLifetimeComesBackAsAFragmentWithNoManageTokenPanel() {
    manageTokens.willReturn(WebPageIT.SCRIPTED);

    MvcTestResult response = submit("https://example.com/very/long", "abc", true);

    assertThat(response).hasStatus(422);
    WebPageIT.assertNoManageTokenPanel(Jsoup.parseBodyFragment(body(response)));
    assertThat(body(response)).doesNotContain(WebPageIT.SCRIPTED);
  }

  @Test
  void theFragmentAndTheFullPageRenderTheSameMarkup() {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    manageTokens.willReturn(WebPageIT.SCRIPTED, WebPageIT.SCRIPTED);
    String fromHtmx =
        Jsoup.parseBodyFragment(body(submit("https://example.com/x", true)))
            .getElementById("shortener")
            .outerHtml();
    String fromFullPage =
        html(submit("https://example.com/x", false)).getElementById("shortener").outerHtml();

    assertThat(fromHtmx.replace("Ab3xK9q", "<code>"))
        .isEqualTo(fromFullPage.replace("Zz9yX8w", "<code>"));
  }

  @Test
  void aRejectionComesBackAsAFragmentWithTheReasonAtTheField() {
    MvcTestResult response = submit("ftp://example.com/file", true);

    assertThat(response).hasStatus(422);
    Document fragment = Jsoup.parseBodyFragment(body(response));
    assertThat(fragment.select("h1")).isEmpty();
    assertThat(fragment.selectFirst("input[name=url]").val()).isEqualTo("ftp://example.com/file");
    assertThat(fragment.getElementById("url-error").text())
        .isEqualTo("Only http:// and https:// web addresses can be shortened.");
  }

  @Test
  void htmxIsConfiguredToSwapRejectionsAndFailures() {
    Element config = html(mvc.get().uri("/").exchange()).selectFirst("meta[name=htmx-config]");

    assertThat(config).isNotNull();
    assertThat(config.attr("content")).contains("\"422\"").contains("\"503\"");
  }

  @Test
  void theShortUrlHasACopyButtonThatStaysHiddenWithoutJavaScript() {
    shortCodes.willReturn("Ab3xK9q");

    Document page = html(submit("https://example.com/very/long", false));

    Element copy = page.selectFirst("button[data-copy]");
    assertThat(copy).isNotNull();
    assertThat(copy.attr("data-copy")).isEqualTo("http://sho.rt/Ab3xK9q");
    assertThat(copy.hasAttr("hidden")).isTrue();
    assertThat(page.selectFirst("script[src=/js/copy.js]")).isNotNull();
    assertThat(mvc.get().uri("/js/copy.js")).hasStatus(200);
  }

  @Test
  void thePageHasAStatusRegionOutsideTheFragmentForAnnouncements() {
    Document page = html(mvc.get().uri("/").exchange());

    Element status = page.getElementById("status");
    assertThat(status).isNotNull();
    assertThat(status.attr("role")).isEqualTo("status");
    assertThat(status.parents()).noneMatch(parent -> parent.id().equals("shortener"));
  }

  @Test
  void theFragmentOffersTheLifetimeField() {
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult response = submit("https://example.com/very/long", "", true);

    WebPageIT.assertLifetimeFieldIsOffered(Jsoup.parseBodyFragment(body(response)));
  }

  @Test
  void aLifetimeComesBackAsAFragmentShowingTheExpiryInUtc() {
    clock.set(Instant.parse("2026-10-08T10:15:00Z"));
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult response = submit("https://example.com/very/long", "30", true);

    assertThat(response).hasStatus(200);
    Document fragment = Jsoup.parseBodyFragment(body(response));
    assertThat(fragment.select("h1")).isEmpty();
    assertThat(fragment.selectFirst("[data-short-url]").text()).isEqualTo("http://sho.rt/Ab3xK9q");
    assertThat(fragment.selectFirst("#result [data-expiry]").text())
        .isEqualTo("Expires on 2026-11-07 10:15 UTC");
  }

  @Test
  void aBlankLifetimeComesBackAsAFragmentWithNoExpiry() {
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult response = submit("https://example.com/very/long", "", true);

    assertThat(response).hasStatus(200);
    Document fragment = Jsoup.parseBodyFragment(body(response));
    assertThat(fragment.selectFirst("[data-short-url]").text()).isEqualTo("http://sho.rt/Ab3xK9q");
    assertThat(fragment.select("[data-expiry]")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "366", "1.5", "abc"})
  void anInvalidLifetimeComesBackAsAFragmentWithTheMessageAtTheField(String days) {
    shortCodes.willReturn("Ab3xK9q");

    MvcTestResult response = submit("https://example.com/very/long", days, true);

    assertThat(response).hasStatus(422);
    Document fragment = Jsoup.parseBodyFragment(body(response));
    assertThat(fragment.select("h1")).isEmpty();
    WebPageIT.assertInvalidLifetimeAtTheField(fragment, "https://example.com/very/long", days);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(404);
  }

  @Test
  void aRuleBreakingLongUrlWithABlankLifetimeComesBackWithTheReasonAtTheUrlField() {
    MvcTestResult response = submit("ftp://example.com/file", "", true);

    assertThat(response).hasStatus(422);
    Document fragment = Jsoup.parseBodyFragment(body(response));
    assertThat(fragment.getElementById("url-error").text())
        .isEqualTo("Only http:// and https:// web addresses can be shortened.");
    assertThat(fragment.getElementById("expires_in_days-error")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"30", "abc"})
  void theFragmentAndTheFullPageRenderTheSameMarkupForALifetime(String days) {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    manageTokens.willReturn(WebPageIT.SCRIPTED, WebPageIT.SCRIPTED);
    String fromHtmx =
        Jsoup.parseBodyFragment(body(submit("https://example.com/x", days, true)))
            .getElementById("shortener")
            .outerHtml();
    String fromFullPage =
        html(submit("https://example.com/x", days, false)).getElementById("shortener").outerHtml();

    assertThat(fromHtmx.replace("Ab3xK9q", "<code>"))
        .isEqualTo(fromFullPage.replace("Zz9yX8w", "<code>"));
  }

  private MvcTestResult submit(String longUrl, String expiresInDays, boolean viaHtmx) {
    var request =
        mvc.post()
            .uri("/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("url", longUrl)
            .param("expires_in_days", expiresInDays);
    if (viaHtmx) {
      request = request.header("HX-Request", "true");
    }
    return request.exchange();
  }

  private MvcTestResult submit(String longUrl, boolean viaHtmx) {
    var request =
        mvc.post()
            .uri("/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("url", longUrl);
    if (viaHtmx) {
      request = request.header("HX-Request", "true");
    }
    return request.exchange();
  }

  private static String body(MvcTestResult response) {
    return new String(response.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private static Document html(MvcTestResult response) {
    return Jsoup.parse(body(response));
  }
}
