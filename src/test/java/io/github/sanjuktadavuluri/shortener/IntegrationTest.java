package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for integration tests at the HTTP seam (plan 0001): the full Spring application over a real
 * SQLite file migrated by Flyway, with a scripted Short Code generator and a fixed clock.
 *
 * <p>Spring caches one application context for every subclass, so they share its database file,
 * generator and clock. Before each test they are reset: the Click Recorder is flushed (so no Click
 * from the previous test is written into this test's database), Flyway rebuilds the schema from its
 * migrations (so the Link Store and Click Store are empty), the Short Code script is emptied and
 * the clock goes back to {@link #NOW}. Tests therefore never depend on each other or on running
 * order.
 *
 * <p>Stored Clicks are observed through the Click Store, after flushing the Click Recorder (spec
 * 0003 seam 3; plan 0001 principle 1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IntegrationTest.ScriptedCodes.class, IntegrationTest.FixedClock.class})
abstract class IntegrationTest {

  static final String BASE_URL = "http://sho.rt";
  static final Path DATABASE = TestDatabases.newFile();

  /** The time the clock shows at the start of every test. */
  static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvcTester mvc;

  @Autowired ScriptedShortCodeGenerator shortCodes;

  @Autowired TestClock clock;

  @Autowired private Flyway flyway;

  @Autowired private QueuedClickRecorder clickRecorder;

  @Autowired private ClickStore clickStore;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("shortener.base-url", () -> BASE_URL);
    registry.add("shortener.database-path", DATABASE::toString);
    // Tests only: lets each test rebuild the schema from the migrations.
    registry.add("spring.flyway.clean-disabled", () -> "false");
  }

  @BeforeEach
  void startFromEmptyStoresAnEmptyScriptAndTheFixedClock() {
    clickRecorder.flush();
    flyway.clean();
    flyway.migrate();
    shortCodes.willReturn();
    clock.set(NOW);
  }

  /** The Clicks stored for a Short Code, oldest first, once every recorded Click is saved. */
  List<Click> storedClicks(String shortCode) {
    clickRecorder.flush();
    return clickStore.listClicks(shortCode);
  }

  /** POSTs {@code {"url": longUrl}} to the API, serialised by Jackson. */
  MvcTestResult postLink(String longUrl) {
    return postBody(JSON.writeValueAsString(Map.of("url", longUrl)), MediaType.APPLICATION_JSON);
  }

  /** POSTs a raw body to the API, for malformed-request tests. */
  MvcTestResult postBody(String body, MediaType contentType) {
    return mvc.post().uri("/links").contentType(contentType).content(body).exchange();
  }

  /** Creates a Link and asserts it succeeded. */
  void createLink(String longUrl) {
    assertThat(postLink(longUrl)).hasStatus(201);
  }

  @TestConfiguration
  static class FixedClock {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock(NOW);
    }
  }

  @TestConfiguration
  static class ScriptedCodes {
    @Bean
    @Primary
    ScriptedShortCodeGenerator scriptedShortCodeGenerator() {
      return new ScriptedShortCodeGenerator();
    }
  }
}
