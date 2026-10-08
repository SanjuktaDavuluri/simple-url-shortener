package io.github.sanjuktadavuluri.shortener.clicks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The maintained list of crawler and link-preview patterns behind the bot Agent Category (#51). */
class BotPatternsTest {

  @Test
  void theListHoldsTheCrawlerAndLinkPreviewPatternsFromTheSpec() {
    assertThat(BotPatterns.PATTERNS)
        .containsExactly(
            "bot",
            "crawler",
            "spider",
            "slurp",
            "facebookexternalhit",
            "embedly",
            "whatsapp",
            "skypeuripreview",
            "bingpreview");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "bot",
        "crawler",
        "spider",
        "slurp",
        "facebookexternalhit",
        "embedly",
        "whatsapp",
        "skypeuripreview",
        "bingpreview"
      })
  void eachPatternMatchesAnywhereInTheUserAgentWhateverItsCase(String pattern) {
    assertThat(BotPatterns.matches("Agent/1.0 " + pattern + "/2.0")).isTrue();
    assertThat(BotPatterns.matches(pattern.toUpperCase(Locale.ROOT))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Mozilla/5.0 (compatible; Yahoo! Slurp; http://help.yahoo.com/help/us/ysearch/slurp)",
        "Mozilla/5.0 (compatible; Baiduspider/2.0; +http://www.baidu.com/search/spider.html)",
        "Mozilla/5.0 (compatible; Embedly/0.2; +http://support.embed.ly/)",
        "Mozilla/5.0 (Windows NT 6.1; WOW64) SkypeUriPreview Preview/0.5",
        "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/534+ (KHTML, like Gecko) BingPreview/1.0b",
        "Mozilla/5.0 (compatible; SemrushCrawler/1.0)"
      })
  void realWorldCrawlerUserAgentsMatch(String userAgent) {
    assertThat(BotPatterns.matches(userAgent)).isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/129.0.0.0 Safari/537.36",
        "curl/8.7.1",
        "python-requests/2.32.3"
      })
  void browsersToolsAndAMissingUserAgentDoNotMatch(String userAgent) {
    assertThat(BotPatterns.matches(userAgent)).isFalse();
  }
}
