package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Issue #3: Links survive a restart of the application on the same database file. Issue #99 (spec
 * 0004): so does a Link's Expiry.
 */
class PersistenceIT extends IntegrationTest {

  @Test
  void linksSurviveARestart() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/kept");

    try (ConfigurableApplicationContext restarted =
        TestApps.start(DATABASE, "--shortener.base-url=" + BASE_URL)) {
      assertThat(TestApps.mvc(restarted).get().uri("/Ab3xK9q"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/kept");
    }
  }

  @Test
  void aLinksExpirySurvivesARestartAndTheExpiredLinkIsStillGone() {
    shortCodes.willReturn("Ab3xK9q");
    // Lifetime 30 at NOW (2026-10-08T09:30:00Z): the Expiry is 2026-11-07T09:30:00Z.
    assertThat(postLink("https://example.com/event", 30))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.expires_at")
        .isEqualTo("2026-11-07T09:30:00Z");

    try (ConfigurableApplicationContext restarted =
        TestApps.startWithConfigurations(
            DATABASE,
            List.of(IntegrationTest.FixedClock.class),
            "--shortener.base-url=" + BASE_URL)) {
      TestClock restartedClock = restarted.getBean(TestClock.class);

      restartedClock.set(Instant.parse("2026-11-07T09:29:59.999Z"));
      assertThat(TestApps.mvc(restarted).get().uri("/Ab3xK9q"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/event");

      restartedClock.set(Instant.parse("2026-11-07T09:30:00Z"));
      assertThat(TestApps.mvc(restarted).get().uri("/Ab3xK9q"))
          .hasStatus(410)
          .hasBodyTextEqualTo("This link has expired.");
    }
  }
}
