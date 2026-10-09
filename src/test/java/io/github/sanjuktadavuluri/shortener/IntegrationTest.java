package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for integration tests at the HTTP seam (plan 0001): the full Spring application over a real
 * SQLite file migrated by Flyway, with scripted Short Code and Manage Token generators and a fixed
 * clock.
 *
 * <p>Spring caches one application context for every subclass, so they share its database file,
 * generators and clock. Before each test they are reset: the Click Recorder is flushed (so no Click
 * from the previous test is written into this test's database), Flyway rebuilds the schema from its
 * migrations (so the Link Store and Click Store are empty), the Short Code and Manage Token scripts
 * are emptied and the clock goes back to {@link #NOW}. Tests therefore never depend on each other
 * or on running order.
 *
 * <p>Stored Clicks are observed through the Click Store, after flushing the Click Recorder (spec
 * 0003 seam 3; plan 0001 principle 1).
 *
 * <p>The application listens on real random ports: the public port, which MockMvc bypasses and
 * {@link #getFromPublicPort} reaches, and the management port (spec 0006 seam 2), reached with
 * {@link #getFromManagementPort}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({
  IntegrationTest.ScriptedCodes.class,
  IntegrationTest.ScriptedTokens.class,
  IntegrationTest.FixedClock.class
})
abstract class IntegrationTest {

  static final String BASE_URL = "http://sho.rt";
  static final Path DATABASE = TestDatabases.newFile();

  /** The time the clock shows at the start of every test. */
  static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvcTester mvc;

  @LocalServerPort int publicPort;

  @LocalManagementPort int managementPort;

  @Autowired ScriptedShortCodeGenerator shortCodes;

  @Autowired ScriptedManageTokenGenerator manageTokens;

  @Autowired TestClock clock;

  @Autowired private JdbcClient jdbc;

  @Autowired private Flyway flyway;

  @Autowired private QueuedClickRecorder clickRecorder;

  @Autowired private ClickStore clickStore;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("shortener.base-url", () -> BASE_URL);
    registry.add("shortener.database-path", DATABASE::toString);
    // Tests only: lets each test rebuild the schema from the migrations.
    registry.add("spring.flyway.clean-disabled", () -> "false");
    // A random management port, never the maintainer's 8081 (spec 0006).
    registry.add("management.server.port", () -> "0");
  }

  @BeforeEach
  void startFromEmptyStoresAnEmptyScriptAndTheFixedClock() {
    clickRecorder.flush();
    flyway.clean();
    flyway.migrate();
    shortCodes.willReturn();
    manageTokens.willReturn();
    clock.set(NOW);
  }

  /** The Clicks stored for a Short Code, oldest first, once every recorded Click is saved. */
  List<Click> storedClicks(String shortCode) {
    clickRecorder.flush();
    return clickStore.listClicks(shortCode);
  }

  /** {@code GET path} over plain HTTP on the management port (spec 0006 seam 2). */
  HttpResponse<String> getFromManagementPort(String path) {
    return PlainHttp.get(managementPort, path);
  }

  /** {@code GET path} over plain HTTP on the public port, through the real servlet container. */
  HttpResponse<String> getFromPublicPort(String path) {
    return PlainHttp.get(publicPort, path);
  }

  /** POSTs {@code {"url": longUrl}} to the API, serialised by Jackson. */
  MvcTestResult postLink(String longUrl) {
    return postBody(JSON.writeValueAsString(Map.of("url", longUrl)), MediaType.APPLICATION_JSON);
  }

  /**
   * POSTs {@code {"url": longUrl, "expires_in_days": expiresInDays}} to the API, serialised by
   * Jackson. {@code expiresInDays} may be {@code null}, which is sent as JSON {@code null}.
   */
  MvcTestResult postLink(String longUrl, Object expiresInDays) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("url", longUrl);
    body.put("expires_in_days", expiresInDays);
    return postBody(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON);
  }

  /** POSTs a raw body to the API, for malformed-request tests. */
  MvcTestResult postBody(String body, MediaType contentType) {
    return mvc.post().uri("/links").contentType(contentType).content(body).exchange();
  }

  /** Creates a Link and asserts it succeeded. */
  void createLink(String longUrl) {
    assertThat(postLink(longUrl)).hasStatus(201);
  }

  /** Creates a Link, asserts it succeeded and returns the Manage Token it was given. */
  String createLinkForItsManageToken(String longUrl) {
    MvcTestResult created = postLink(longUrl);
    assertThat(created).hasStatus(201);
    return JSON.readTree(created.getResponse().getContentAsByteArray())
        .get("manage_token")
        .asString();
  }

  /**
   * The Manage Token hash stored with a Link, read straight from {@code links}: the column is the
   * observable promise that only the hash is kept (spec 0005, story 6). Empty for a Link that has
   * none.
   */
  Optional<String> storedManageTokenHash(String shortCode) {
    return jdbc.sql("SELECT manage_token_hash FROM links WHERE short_code = :shortCode")
        .param("shortCode", shortCode)
        .query((row, i) -> Optional.ofNullable(row.getString(1)))
        .single();
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
  static class ScriptedTokens {
    @Bean
    @Primary
    ScriptedManageTokenGenerator scriptedManageTokenGenerator() {
      return new ScriptedManageTokenGenerator();
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
