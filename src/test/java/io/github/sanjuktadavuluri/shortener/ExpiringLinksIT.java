package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.DeviceClass;
import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issue #97 (spec 0004, seam 1): creating a Link with a Lifetime through the HTTP seam, and its
 * Short URL answering {@code 410 Gone} from its Expiry on. Time is moved with the {@link
 * TestClock}. Issue #99 (stories 19 and 21): an Expired Link keeps its Clicks, and Links from
 * before this change migrate to V3 and keep Redirecting with no Expiry.
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

    assertThat(postLink("https://example.com/event", expiresInDays))
        .hasStatus(201)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "short_code": "Ab3xK9q",
              "short_url": "http://sho.rt/Ab3xK9q",
              "long_url": "https://example.com/event",
              "expires_at": "%s"
            }
            """
                .formatted(expiresAt));
  }

  @Test
  void aLinkCreatedWithANullLifetimeReportsNoExpiry() {
    shortCodes.willReturn("Ab3xK9q");

    assertThat(postLink("https://example.com/forever", null))
        .hasStatus(201)
        .bodyJson()
        .isStrictlyEqualTo(NEVER_EXPIRES);
  }

  @Test
  void aLinkCreatedWithoutALifetimeReportsNoExpiry() {
    shortCodes.willReturn("Ab3xK9q");

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

  @Test
  void anExpiredLinksClicksFromBeforeItsExpiryAreKept() {
    createLinkExpiringIn30Days();
    Instant early = Instant.parse("2026-10-20T08:00:00Z");
    Instant late = Instant.parse("2026-11-07T09:59:59.999Z");
    clock.set(early);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);
    clock.set(late);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);

    clock.set(Instant.parse("2026-12-01T00:00:00Z"));
    assertThat(storedClicks("Ab3xK9q"))
        .extracting(Click::shortCode, Click::clickedAt)
        .containsExactly(tuple("Ab3xK9q", early), tuple("Ab3xK9q", late));

    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(410);
    assertThat(storedClicks("Ab3xK9q")).extracting(Click::clickedAt).containsExactly(early, late);
  }

  @Test
  void
      theExpiryMigrationAppliesToARelease2DatabaseWhoseLinksKeepRedirectingWithNoExpiryAndKeepTheirClicks()
          throws SQLException {
    Path release2 = TestDatabases.newFile();
    String url = "jdbc:sqlite:" + release2;
    // A Release 2 database: the V1 and V2 migrations applied, already holding Links and Clicks.
    Flyway.configure()
        .dataSource(url, null, null)
        .locations("classpath:db/migration")
        .target("2")
        .load()
        .migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO links (short_code, long_url) VALUES"
              + " ('Old1234', 'https://example.com/r2'), ('Old5678', 'https://example.com/other')");
      statement.executeUpdate(
          "INSERT INTO clicks (short_code, clicked_at, referrer_host, agent_category, device_class)"
              + " VALUES ('Old1234', '2026-09-01T12:00:00.000Z', 'blog.example.com', 'browser',"
              + " 'mobile'), ('Old1234', '2026-09-02T12:00:00.000Z', NULL, 'bot', 'desktop')");
    }

    try (ConfigurableApplicationContext app =
        TestApps.startWithConfigurations(
            release2,
            List.of(IntegrationTest.FixedClock.class),
            "--shortener.base-url=" + BASE_URL)) {
      // Ten years on, a Link from before Expiring Links still Redirects: it never expires.
      app.getBean(TestClock.class).set(Instant.parse("2036-10-08T10:00:00Z"));
      MockMvcTester restarted = TestApps.mvc(app);
      assertThat(restarted.get().uri("/Old1234"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/r2");
      assertThat(restarted.head().uri("/Old5678"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/other");
      LinkStore links = app.getBean(LinkStore.class);
      assertThat(links.findDestination("Old1234")).hasValueSatisfying(this::hasNoExpiry);
      assertThat(links.findDestination("Old5678")).hasValueSatisfying(this::hasNoExpiry);

      // Its earlier Clicks are untouched, and the Redirect above added one more.
      app.getBean(QueuedClickRecorder.class).flush();
      assertThat(app.getBean(ClickStore.class).listClicks("Old1234"))
          .extracting(
              Click::clickedAt, Click::referrerHost, Click::agentCategory, Click::deviceClass)
          .containsExactly(
              tuple(
                  Instant.parse("2026-09-01T12:00:00Z"),
                  Optional.of("blog.example.com"),
                  AgentCategory.BROWSER,
                  DeviceClass.MOBILE),
              tuple(
                  Instant.parse("2026-09-02T12:00:00Z"),
                  Optional.empty(),
                  AgentCategory.BOT,
                  DeviceClass.DESKTOP),
              tuple(
                  Instant.parse("2036-10-08T10:00:00Z"),
                  Optional.empty(),
                  AgentCategory.OTHER,
                  DeviceClass.DESKTOP));
    }
  }

  private void hasNoExpiry(LinkStore.Destination destination) {
    assertThat(destination.expiry()).isEmpty();
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

  private static final String NEVER_EXPIRES =
      """
      {
        "short_code": "Ab3xK9q",
        "short_url": "http://sho.rt/Ab3xK9q",
        "long_url": "https://example.com/forever",
        "expires_at": null
      }
      """;
}
