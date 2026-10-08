package io.github.sanjuktadavuluri.shortener.clicks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Seam 2 of spec 0003: raw header values in, Click attributes out (issue #51). */
class ClickClassifierTest {

  private static final String CHROME_ON_DESKTOP =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/129.0.0.0 Safari/537.36";
  private static final String FIREFOX_ON_DESKTOP =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.6; rv:131.0) Gecko/20100101 Firefox/131.0";
  private static final String SAFARI_ON_IPHONE =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15"
          + " (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
  private static final String CHROME_ON_ANDROID =
      "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/129.0.0.0 Mobile Safari/537.36";
  private static final String GOOGLEBOT =
      "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

  // Referrer Host

  @ParameterizedTest
  @CsvSource({
    "https://News.Example.COM/, news.example.com",
    "HTTP://EXAMPLE.ORG, example.org",
    "https://alice:secret@example.com:8443/, example.com",
    "http://example.com:80, example.com",
    "https://example.com/private/page?token=abc#section, example.com",
    "http://blog.example.net/2026/10/post.html?utm_source=x, blog.example.net"
  })
  void theReferrerHostIsTheLowerCaseHostWithoutPortUserInfoPathOrQuery(
      String referer, String referrerHost) {
    assertThat(ClickClassifier.classify(referer, CHROME_ON_DESKTOP).referrerHost())
        .contains(referrerHost);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void aMissingRefererGivesNoReferrerHost(String referer) {
    assertThat(ClickClassifier.classify(referer, CHROME_ON_DESKTOP).referrerHost()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"https://exa mple.com/", "not a url at all", "http://", "https:///path", "://x"})
  void aMalformedRefererGivesNoReferrerHost(String referer) {
    assertThat(ClickClassifier.classify(referer, CHROME_ON_DESKTOP).referrerHost()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "android-app://com.google.android.gm/",
        "ftp://example.com/file.txt",
        "file:///etc/hosts",
        "javascript:alert(1)",
        "example.com/page"
      })
  void aRefererThatIsNotAnHttpOrHttpsUrlGivesNoReferrerHost(String referer) {
    assertThat(ClickClassifier.classify(referer, CHROME_ON_DESKTOP).referrerHost()).isEmpty();
  }

  // Agent Category and Device Class

  @ParameterizedTest
  @ValueSource(
      strings = {
        GOOGLEBOT,
        "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
        "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",
        "Twitterbot/1.0",
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
        "WhatsApp/2.23.20.0 A",
        "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)"
      })
  void knownCrawlersAndLinkPreviewFetchersAreBotsOnDesktop(String userAgent) {
    assertThat(ClickClassifier.classify(null, userAgent))
        .isEqualTo(
            new ClickClassification(Optional.empty(), AgentCategory.BOT, DeviceClass.DESKTOP));
  }

  @Test
  void aBotWhoseUserAgentStartsWithMozillaIsStillABot() {
    assertThat(GOOGLEBOT).startsWith("Mozilla/");

    assertThat(ClickClassifier.classify(null, GOOGLEBOT).agentCategory())
        .isEqualTo(AgentCategory.BOT);
  }

  @ParameterizedTest
  @ValueSource(strings = {CHROME_ON_DESKTOP, FIREFOX_ON_DESKTOP})
  void desktopBrowsersAreBrowserAgentsOnDesktop(String userAgent) {
    ClickClassification classification = ClickClassifier.classify(null, userAgent);

    assertThat(classification.agentCategory()).isEqualTo(AgentCategory.BROWSER);
    assertThat(classification.deviceClass()).isEqualTo(DeviceClass.DESKTOP);
  }

  @ParameterizedTest
  @ValueSource(strings = {SAFARI_ON_IPHONE, CHROME_ON_ANDROID})
  void mobileBrowsersAreBrowserAgentsOnMobile(String userAgent) {
    ClickClassification classification = ClickClassifier.classify(null, userAgent);

    assertThat(classification.agentCategory()).isEqualTo(AgentCategory.BROWSER);
    assertThat(classification.deviceClass()).isEqualTo(DeviceClass.MOBILE);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"curl/8.7.1", "Wget/1.24.5", "python-requests/2.32.3"})
  void toolsAndAMissingUserAgentAreOtherAgentsOnDesktop(String userAgent) {
    ClickClassification classification = ClickClassifier.classify(null, userAgent);

    assertThat(classification.agentCategory()).isEqualTo(AgentCategory.OTHER);
    assertThat(classification.deviceClass()).isEqualTo(DeviceClass.DESKTOP);
  }

  @ParameterizedTest
  @CsvSource({
    "Mozilla/5.0 (Linux; Android 14; Pixel 8), MOBILE",
    "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X), MOBILE",
    "Mozilla/5.0 (Mobile; rv:48.0) Gecko/48.0 Firefox/48.0, MOBILE",
    "Mozilla/5.0 (X11; Linux x86_64), DESKTOP"
  })
  void theDeviceClassIsMobileOnlyWithAMobileMarker(String userAgent, DeviceClass deviceClass) {
    assertThat(ClickClassifier.classify(null, userAgent).deviceClass()).isEqualTo(deviceClass);
  }
}
