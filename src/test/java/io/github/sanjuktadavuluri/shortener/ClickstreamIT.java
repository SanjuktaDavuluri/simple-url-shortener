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
import java.time.Duration;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Issue #52 (spec 0003, seam 3): every successful Redirect stores exactly one Click, observed
 * through the Click Store after flushing the Click Recorder. Nothing else stores a Click.
 */
class ClickstreamIT extends IntegrationTest {

  private static final String CHROME_ON_ANDROID =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/126.0.0.0 Mobile Safari/537.36";

  private static final String GOOGLEBOT =
      "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

  @Test
  void aRedirectStoresExactlyOneClickWithItsShortCodeTimeReferrerHostAndCategories() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/very/long");

    assertThat(
            mvc.get()
                .uri("/Ab3xK9q")
                .header(HttpHeaders.REFERER, "https://News.Example.org:8443/articles/42?ref=feed")
                .header(HttpHeaders.USER_AGENT, CHROME_ON_ANDROID))
        .hasStatus(302);

    assertThat(storedClicks("Ab3xK9q"))
        .containsExactly(
            new Click(
                "Ab3xK9q",
                NOW,
                Optional.of("news.example.org"),
                AgentCategory.BROWSER,
                DeviceClass.MOBILE));
  }

  @Test
  void anUnknownShortCodeStoresNoClick() {
    assertThat(mvc.get().uri("/Nope123")).hasStatus(404);

    assertThat(storedClicks("Nope123")).isEmpty();
  }

  @Test
  void aPathThatCannotBeAShortCodeStoresNoClick() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/very/long");

    assertThat(mvc.get().uri("/Ab3xK9qX")).hasStatus(404);
    assertThat(mvc.get().uri("/Ab3xK9q/more")).hasStatus(404);

    assertThat(storedClicks("Ab3xK9q")).isEmpty();
    assertThat(storedClicks("Ab3xK9qX")).isEmpty();
  }

  @Test
  void aHeadRequestStoresNoClick() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/very/long");

    assertThat(mvc.head().uri("/Ab3xK9q")).hasStatus(302);

    assertThat(storedClicks("Ab3xK9q")).isEmpty();
  }

  @Test
  void creatingLinksAndViewingTheWebPageStoreNoClick() {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    createLink("https://example.com/from-the-api");
    assertThat(
            mvc.post()
                .uri("/")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("url", "https://example.com/from-the-page"))
        .hasStatus(200);
    assertThat(mvc.get().uri("/")).hasStatus(200);

    assertThat(storedClicks("Ab3xK9q")).isEmpty();
    assertThat(storedClicks("Zz9yX8w")).isEmpty();
  }

  @Test
  void nRedirectsOfOneLinkStoreNClicksOldestFirstAndEachLinkKeepsItsOwn() {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    createLink("https://example.com/first");
    createLink("https://example.com/second");

    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);
    clock.advance(Duration.ofSeconds(1));
    assertThat(mvc.get().uri("/Zz9yX8w")).hasStatus(302);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);
    clock.advance(Duration.ofMillis(1500));
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);

    assertThat(storedClicks("Ab3xK9q"))
        .extracting(Click::shortCode, Click::clickedAt)
        .containsExactly(
            tuple("Ab3xK9q", NOW),
            tuple("Ab3xK9q", NOW.plusSeconds(1)),
            tuple("Ab3xK9q", NOW.plusMillis(2500)));
    assertThat(storedClicks("Zz9yX8w"))
        .extracting(Click::shortCode, Click::clickedAt)
        .containsExactly(tuple("Zz9yX8w", NOW.plusSeconds(1)));
  }

  @Test
  void theFullRefererAndTheRawUserAgentAppearNowhereInTheStoredClick() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/very/long");
    String referer = "https://reader:secret@Blog.Example.com:8080/private/draft?token=abc123#notes";

    assertThat(
            mvc.get()
                .uri("/Ab3xK9q")
                .header(HttpHeaders.REFERER, referer)
                .header(HttpHeaders.USER_AGENT, GOOGLEBOT))
        .hasStatus(302);

    assertThat(storedClicks("Ab3xK9q"))
        .singleElement()
        .satisfies(
            click -> {
              assertThat(click.referrerHost()).hasValue("blog.example.com");
              assertThat(click.agentCategory()).isEqualTo(AgentCategory.BOT);
              assertThat(click.deviceClass()).isEqualTo(DeviceClass.DESKTOP);
              assertThat(click.toString())
                  .doesNotContain(referer)
                  .doesNotContain(GOOGLEBOT)
                  .doesNotContain("reader")
                  .doesNotContain("secret")
                  .doesNotContain("private")
                  .doesNotContain("token")
                  .doesNotContain("Googlebot");
            });
  }

  @Test
  void theClicksMigrationAppliesToARelease1DatabaseWhoseLinksKeepRedirecting() throws SQLException {
    Path release1 = TestDatabases.newFile();
    String url = "jdbc:sqlite:" + release1;
    // A Release 1 database: only the V1 migration applied, already holding a Link.
    Flyway.configure()
        .dataSource(url, null, null)
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO links (short_code, long_url) VALUES ('Old1234', 'https://example.com/r1')");
    }

    try (ConfigurableApplicationContext app =
        TestApps.start(release1, "--shortener.base-url=" + BASE_URL)) {
      assertThat(TestApps.mvc(app).get().uri("/Old1234"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/r1");

      app.getBean(QueuedClickRecorder.class).flush();
      assertThat(app.getBean(ClickStore.class).listClicks("Old1234"))
          .singleElement()
          .satisfies(
              click -> {
                assertThat(click.shortCode()).isEqualTo("Old1234");
                assertThat(click.referrerHost()).isEmpty();
                assertThat(click.agentCategory()).isEqualTo(AgentCategory.OTHER);
                assertThat(click.deviceClass()).isEqualTo(DeviceClass.DESKTOP);
              });
    }
  }
}
