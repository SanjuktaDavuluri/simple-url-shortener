package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Issue #52 (spec 0003): the Click Recorder settings bind from their environment variables, with
 * conservative defaults when they are not set.
 */
class ClickSettingsIT {

  @Test
  void theClickSettingsDefaultToTenThousandQueuedFiveHundredPerBatchAndOneSecond() {
    try (ConfigurableApplicationContext app = TestApps.start(TestDatabases.newFile())) {
      assertThat(app.getBean(ShortenerProperties.class).clicks())
          .isEqualTo(new ShortenerProperties.Clicks(10_000, 500, Duration.ofSeconds(1)));
    }
  }

  @Test
  void theClickSettingsBindFromTheirEnvironmentVariables() {
    try (ConfigurableApplicationContext app =
        TestApps.startWithEnvironment(
            TestDatabases.newFile(),
            Map.of(
                "CLICK_QUEUE_CAPACITY", "250",
                "CLICK_BATCH_SIZE", "25",
                "CLICK_FLUSH_INTERVAL", "200ms"))) {
      assertThat(app.getBean(ShortenerProperties.class).clicks())
          .isEqualTo(new ShortenerProperties.Clicks(250, 25, Duration.ofMillis(200)));
    }
  }
}
