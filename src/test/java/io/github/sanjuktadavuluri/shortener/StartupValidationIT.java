package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;

/** Issue #118 (spec 0006): bad configuration stops startup, naming the variable and its value. */
class StartupValidationIT {

  private static final String BASE = "http://sho.rt";

  @ParameterizedTest
  @CsvSource({
    "BASE_URL,/relative",
    "BASE_URL,ftp://sho.rt",
    "BASE_URL,http://sho.rt?x=1",
    "BASE_URL,http://sho.rt#frag",
    "CLICK_BATCH_SIZE,0",
    "CLICK_QUEUE_CAPACITY,0",
    "CLICK_QUEUE_CAPACITY,-5",
    "CLICK_FLUSH_INTERVAL,0s",
    "CLICK_SHUTDOWN_TIMEOUT,0s"
  })
  void aBadSettingStopsStartupNamingItsVariableAndValue(String variable, String value) {
    assertThatThrownBy(
            () ->
                TestApps.startWithEnvironment(TestDatabases.newFile(), Map.of(variable, value)))
        .hasStackTraceContaining(variable)
        .hasStackTraceContaining(value);
  }

  @Test
  void aBatchSizeLargerThanTheQueueCapacityStopsStartupNamingBoth() {
    assertThatThrownBy(
            () ->
                TestApps.startWithEnvironment(
                    TestDatabases.newFile(),
                    Map.of("CLICK_QUEUE_CAPACITY", "10", "CLICK_BATCH_SIZE", "11")))
        .hasStackTraceContaining("CLICK_BATCH_SIZE")
        .hasStackTraceContaining("CLICK_QUEUE_CAPACITY");
  }

  @Test
  void aTrailingSlashOnTheBaseUrlGivesShortUrlsWithoutDoubleSlashAndStillRejectsSelfLinks() {
    try (ConfigurableApplicationContext app =
        TestApps.startWithEnvironment(TestDatabases.newFile(), Map.of("BASE_URL", BASE + "/"))) {
      assertThat(
              TestApps.mvc(app)
                  .post()
                  .uri("/links")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"url\": \"https://example.com\"}"))
          .hasStatus(201)
          .bodyJson()
          .extractingPath("$.short_url")
          .asString()
          .matches("http://sho\\.rt/[A-Za-z0-9]{7}");
      assertThat(
              TestApps.mvc(app)
                  .post()
                  .uri("/links")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"url\": \"" + BASE + "/Ab3xK9q\"}"))
          .hasStatus(422);
    }
  }
}
