package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue #55 (spec 0003 story 18, seam 3): Clicks still queued when the app shuts down normally are
 * saved before it exits, because the Click Recorder stops after the web server has stopped taking
 * requests.
 */
class ClickShutdownIT {

  private static final String BASE_URL = "http://sho.rt";

  /** Long enough that only the shutdown can save the Click. */
  private static final String LONG_FLUSH_INTERVAL = "--shortener.clicks.flush-interval=10m";

  @Test
  void aQueuedClickIsSavedOnANormalShutdownAndFoundAfterARestart() {
    Path database = TestDatabases.newFile();
    String shortCode;
    try (ConfigurableApplicationContext app =
        TestApps.start(database, "--shortener.base-url=" + BASE_URL, LONG_FLUSH_INTERVAL)) {
      shortCode = createLink(app, "https://example.com/kept-through-shutdown");
      assertThat(TestApps.mvc(app).get().uri("/" + shortCode)).hasStatus(302);
      assertThat(app.getBean(QueuedClickRecorder.class).stats().pending()).isEqualTo(1);
    }

    try (ConfigurableApplicationContext restarted =
        TestApps.start(database, "--shortener.base-url=" + BASE_URL)) {
      assertThat(restarted.getBean(ClickStore.class).listClicks(shortCode))
          .extracting(Click::shortCode)
          .containsExactly(shortCode);
    }
  }

  @Test
  void theClickRecorderStopsAfterTheWebServerHasStoppedTakingRequests() {
    try (ConfigurableApplicationContext app = TestApps.start(TestDatabases.newFile())) {
      Map<String, SmartLifecycle> lifecycles = app.getBeansOfType(SmartLifecycle.class);
      int recorderPhase = app.getBean(QueuedClickRecorder.class).getPhase();

      // Spring stops higher phases first: the web server's graceful shutdown and its stop, then
      // the Click Recorder, so Redirects served while the server drains are still saved.
      assertThat(lifecycles.values())
          .anySatisfy(
              lifecycle ->
                  assertThat(lifecycle).isInstanceOf(WebServerGracefulShutdownLifecycle.class));
      assertThat(lifecycles.values())
          .filteredOn(lifecycle -> isWebServerLifecycle(lifecycle))
          .hasSizeGreaterThanOrEqualTo(2)
          .allSatisfy(lifecycle -> assertThat(lifecycle.getPhase()).isGreaterThan(recorderPhase));
    }
  }

  private static boolean isWebServerLifecycle(SmartLifecycle lifecycle) {
    return lifecycle.getClass().getName().startsWith("org.springframework.boot.web.server.");
  }

  private static String createLink(ConfigurableApplicationContext app, String longUrl) {
    byte[] body =
        TestApps.mvc(app)
            .post()
            .uri("/links")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"" + longUrl + "\"}")
            .exchange()
            .getResponse()
            .getContentAsByteArray();
    JsonNode created = JsonMapper.builder().build().readTree(body);
    return created.get("short_code").asString();
  }
}
