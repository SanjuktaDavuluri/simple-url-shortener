package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issue #112 (spec 0005 seam 2; stories 4, 9, 18, 20, 21 and 24): the Stats web page. The token
 * goes in a {@code POST} body, never the URL; every failure is the same {@code 404} form; HTMX gets
 * only the fragment.
 */
class StatsPageIT extends IntegrationTest {

  private static final String TOKEN = WebPageIT.SCRIPTED;
  private static final String MESSAGE = "No stats found for that Short Code and manage token.";
  private static final String FIREFOX =
      "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0";

  @Autowired JdbcClient jdbcClient;

  @Test
  void theFormIsRenderedNotCachedAndEmpty() {
    MvcTestResult page = mvc.get().uri("/stats").exchange();

    assertThat(page).hasStatus(200).hasHeader("Cache-Control", "no-store");
    Document html = html(page);
    assertThat(html.selectFirst("input[name=short_code]").val()).isEmpty();
    assertThat(html.selectFirst("form[method=post][action=/stats]")).isNotNull();
    assertThat(html.select("label[for=short_code]").text()).isNotBlank();
  }

  @Test
  void theShortCodeQueryParameterPreFillsTheShortCodeField() {
    Document html = html(mvc.get().uri("/stats").param("short_code", "Ab3xK9q").exchange());

    assertThat(html.selectFirst("input[name=short_code]").val()).isEqualTo("Ab3xK9q");
    assertThat(html.selectFirst("input[name=manage_token]").val()).isEmpty();
  }

  @Test
  void aTokenInTheQueryStringIsNeitherPreFilledNorUsed() {
    createLinkWithCode("Ab3xK9q");

    MvcTestResult page =
        mvc.get()
            .uri("/stats")
            .param("short_code", "Ab3xK9q")
            .param("manage_token", TOKEN)
            .exchange();

    assertThat(page).hasStatus(200);
    assertThat(body(page)).doesNotContain(TOKEN);
    assertThat(html(page).selectFirst("input[name=manage_token]").val()).isEmpty();
    assertThat(html(page).select("#stats-clicks")).isEmpty();
  }

  @Test
  void theTokenFieldIsAPasswordFieldWithAutocompleteOff() {
    Element token =
        html(mvc.get().uri("/stats").exchange()).selectFirst("input[name=manage_token]");

    assertThat(token.attr("type")).isEqualTo("password");
    assertThat(token.attr("autocomplete")).isEqualTo("off");
  }

  @Test
  void theRightTokenRendersTheStatsNotCached() {
    createLinkWithCode("Ab3xK9q");
    redirect("Ab3xK9q");
    clock.advance(Duration.ofSeconds(1));
    redirect("Ab3xK9q");
    flushClicks();

    MvcTestResult page = post("Ab3xK9q", TOKEN, false);

    assertThat(page).hasStatus(200).hasHeader("Cache-Control", "no-store");
    Document html = html(page);
    assertThat(html.selectFirst("#stats-clicks").text()).contains("2");
    assertThat(html.selectFirst("#stats-bot-clicks").text()).contains("0");
    assertThat(html.select("#stats-per-day tbody tr")).hasSize(30);
    assertThat(html.select("#stats-per-day tbody tr").last().text()).contains("2026-10-08", "2");
    assertThat(html.select("#stats-per-day .bar")).hasSize(30);
    assertThat(html.text()).contains("https://example.com/very/long", "http://sho.rt/Ab3xK9q");
    assertThat(html.selectFirst("#stats-lag").text()).containsIgnoringCase("lag");
    assertThat(html.select("script:not([src]), style, [style]")).isEmpty();
    assertThat(body(page)).doesNotContain(TOKEN);
  }

  @Test
  void aShortUrlStartingWithTheBaseUrlWorksLikeTheBareShortCode() {
    createLinkWithCode("Ab3xK9q");

    MvcTestResult page = post("http://sho.rt/Ab3xK9q", TOKEN, false);

    assertThat(page).hasStatus(200);
    assertThat(html(page).selectFirst("#stats-clicks")).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void everyFailureIsTheSame404FormWithTheMessage(boolean viaHtmx) {
    createLinkWithCode("Ab3xK9q");
    jdbcClient
        .sql("INSERT INTO links (short_code, long_url) VALUES ('Old1234', 'https://x.test/old')")
        .update();

    for (String[] attempt :
        new String[][] {
          {"Zz9yX8w", TOKEN},
          {"Old1234", TOKEN},
          {"Ab3xK9q", ""},
          {"Ab3xK9q", "wrong-token"},
        }) {
      MvcTestResult page = post(attempt[0], attempt[1], viaHtmx);

      assertThat(page).hasStatus(404).hasHeader("Cache-Control", "no-store");
      Document html = Jsoup.parse(body(page));
      assertThat(html.selectFirst("#stats-error").text()).isEqualTo(MESSAGE);
      assertThat(html.selectFirst("input[name=short_code]")).isNotNull();
      assertThat(body(page)).doesNotContain("wrong-token");
    }
  }

  @Test
  void anHtmxRequestGetsOnlyTheFragmentOnBothStatusesAndAPlainOneTheFullPage() {
    createLinkWithCode("Ab3xK9q");

    for (String token : new String[] {TOKEN, "wrong"}) {
      Document fragment = Jsoup.parseBodyFragment(body(post("Ab3xK9q", token, true)));
      assertThat(fragment.select("h1, title, script")).isEmpty();
      assertThat(fragment.body().children()).hasSize(1);
      assertThat(fragment.body().child(0).id()).isEqualTo("stats");
      assertThat(html(post("Ab3xK9q", token, false)).select("h1, title")).isNotEmpty();
    }
    Document form = Jsoup.parseBodyFragment(body(post("Ab3xK9q", "wrong", true)));
    assertThat(form.selectFirst("form").attr("hx-post")).isEqualTo("/stats");
  }

  @Test
  void htmxSwapsThe404OnTheStatsPage() {
    Element config = html(mvc.get().uri("/stats").exchange()).selectFirst("meta[name=htmx-config]");

    assertThat(config.attr("content")).contains("\"404\"");
  }

  @Test
  void theCreateResultLinksToTheStatsPageWithTheShortCodeOnly() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(TOKEN);

    MvcTestResult page =
        mvc.post()
            .uri("/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("url", "https://example.com/very/long")
            .exchange();

    Element link = html(page).selectFirst("#result a[data-stats-link]");
    assertThat(link.text()).isEqualTo("See its stats");
    assertThat(link.attr("href")).isEqualTo("/stats?short_code=Ab3xK9q");
  }

  private void createLinkWithCode(String code) {
    shortCodes.willReturn(code);
    manageTokens.willReturn(TOKEN);
    assertThat(
            mvc.post()
                .uri("/")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("url", "https://example.com/very/long"))
        .hasStatus(200);
  }

  private void redirect(String code) {
    assertThat(mvc.get().uri("/" + code).header("User-Agent", FIREFOX)).hasStatus(302);
  }

  private MvcTestResult post(String shortCode, String token, boolean viaHtmx) {
    MockMvcTester.MockMvcRequestBuilder request =
        mvc.post()
            .uri("/stats")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("short_code", shortCode)
            .param("manage_token", token);
    if (viaHtmx) {
      request = request.header("HX-Request", "true");
    }
    return request.exchange();
  }

  private static String body(MvcTestResult page) {
    return new String(page.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private static Document html(MvcTestResult page) {
    return Jsoup.parse(body(page));
  }
}
