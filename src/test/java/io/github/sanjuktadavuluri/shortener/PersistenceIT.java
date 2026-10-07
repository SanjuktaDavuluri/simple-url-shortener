package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** Issue #3: Links survive a restart of the application on the same database file. */
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
}
