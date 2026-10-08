package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.sanjuktadavuluri.shortener.clicks.Click;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issue #97 (spec 0004, seam 1): creating a Link with a Lifetime through the HTTP seam, and its
 * Short URL answering {@code 410 Gone} from its Expiry on. Time is moved with the {@link
 * TestClock}.
 */
class ExpiringLinksIT extends IntegrationTest {

  private static final Instant CREATED = Instant.parse("2026-10-08T10:00:00Z");

  @BeforeEach
  void theClockShowsTheCreationTime() {
    clock.set(CREATED);
  }

  @ParameterizedTest
  @CsvSource({
    "30, 2026-11-07T10:00:00Z",
    "1, 2026-10-09T10:00:00Z",
    "365, 2027-10-08T10:00:00Z",
  })
  void aLinkCreatedWithALifetimeReturnsItsExpiry(int expiresInDays, String expiresAt) {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(MANAGE_TOKEN);

    assertThat(postLink("https://example.com/event", expiresInDays))
        .hasStatus(201)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "short_code": "Ab3xK9q",
              "short_url": "http://sho.rt/Ab3xK9q",
              "long_url": "https://example.com/event",
              "manage_token": "first-scripted-manage-token-for-tests-00001",
              "expires_at": "%s"
            }
            """
                .formatted(expiresAt));
  }

  @Test
  void aLinkCreatedWithANullLifetimeReportsNoExpiry() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(MANAGE_TOKEN);

    assertThat(postLink("https://example.com/forever", null))
        .hasStatus(201)
        .bodyJson()
        .isStrictlyEqualTo(NEVER_EXPIRES);
  }

  @Test
  void aLinkCreatedWithoutALifetimeReportsNoExpiry() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(MANAGE_TOKEN);

    assertThat(postLink("https://example.com/forever"))
        .hasStatus(201)
        .bodyJson()
        .isStrictlyEqualTo(NEVER_EXPIRES);
  }

  @Test
  void oneMillisecondBeforeItsExpiryALinkStillRedirects() {
    createLinkExpiringIn30Days();
    clock.set(Instant.parse("2026-11-07T09:59:59.999Z"));

    assertThat(mvc.get().uri("/Ab3xK9q"))
        .hasStatus(302)
        .hasHeader("Location", "https://example.com/event")
        .hasHeader("Cache-Control", "no-store");
  }

  @Test
  void fromTheExactInstantOfItsExpiryTheExpiredLinkIsGone() {
    createLinkExpiringIn30Days();
    clock.set(EXPIRY);

    assertThatGoneWithItsBody(mvc.get().uri("/Ab3xK9q").exchange());
  }

  @Test
  void longAfterItsExpiryTheExpiredLinkIsStillGone() {
    createLinkExpiringIn30Days();
    clock.set(Instant.parse("2036-10-08T10:00:00Z"));

    assertThatGoneWithItsBody(mvc.get().uri("/Ab3xK9q").exchange());
  }

  @Test
  void aHeadRequestToAnExpiredLinkGetsTheSameAnswerWithoutABody() {
    createLinkExpiringIn30Days();
    clock.set(EXPIRY);

    MvcTestResult head = mvc.head().uri("/Ab3xK9q").exchange();

    assertThat(head)
        .hasStatus(410)
        .hasHeader("Content-Type", "text/plain;charset=UTF-8")
        .hasHeader("Cache-Control", "no-store")
        .doesNotContainHeader("Location");
    assertThat(head).body().isEmpty();
  }

  @Test
  void anUnknownShortCodeIsStillNotFound() {
    clock.set(EXPIRY);

    assertThat(mvc.get().uri("/Nope123")).hasStatus(404);
  }

  @Test
  void aLinkCreatedWithoutALifetimeStillRedirectsTenYearsLater() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/forever");
    clock.set(Instant.parse("2036-10-08T10:00:00Z"));

    assertThat(mvc.get().uri("/Ab3xK9q"))
        .hasStatus(302)
        .hasHeader("Location", "https://example.com/forever")
        .hasHeader("Cache-Control", "no-store");
  }

  @Test
  void followingAnExpiredLinkStoresNoClick() {
    createLinkExpiringIn30Days();
    clock.set(EXPIRY);

    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(410);
    assertThat(mvc.head().uri("/Ab3xK9q")).hasStatus(410);

    assertThat(storedClicks("Ab3xK9q")).isEmpty();
  }

  @Test
  void followingALinkBeforeItsExpiryStoresExactlyOneClick() {
    createLinkExpiringIn30Days();
    Instant justBefore = Instant.parse("2026-11-07T09:59:59.999Z");
    clock.set(justBefore);

    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);

    assertThat(storedClicks("Ab3xK9q"))
        .extracting(Click::shortCode, Click::clickedAt)
        .containsExactly(tuple("Ab3xK9q", justBefore));
  }

  /** Lifetime 30 at {@link #CREATED}: the Expiry is {@link #EXPIRY}. */
  private void createLinkExpiringIn30Days() {
    shortCodes.willReturn("Ab3xK9q");
    assertThat(postLink("https://example.com/event", 30))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.expires_at")
        .isEqualTo("2026-11-07T10:00:00Z");
  }

  private static void assertThatGoneWithItsBody(MvcTestResult result) {
    assertThat(result)
        .hasStatus(410)
        .hasHeader("Content-Type", "text/plain;charset=UTF-8")
        .hasHeader("Cache-Control", "no-store")
        .doesNotContainHeader("Location")
        .hasBodyTextEqualTo("This link has expired.");
  }

  private static final Instant EXPIRY = Instant.parse("2026-11-07T10:00:00Z");

  private static final String MANAGE_TOKEN = "first-scripted-manage-token-for-tests-00001";

  private static final String NEVER_EXPIRES =
      """
      {
        "short_code": "Ab3xK9q",
        "short_url": "http://sho.rt/Ab3xK9q",
        "long_url": "https://example.com/forever",
        "manage_token": "first-scripted-manage-token-for-tests-00001",
        "expires_at": null
      }
      """;
}
