package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
 * SQLite file migrated by Flyway, with a scripted Short Code generator.
 *
 * <p>Spring caches one application context for every subclass, so they share its database file and
 * generator. Before each test both are reset: Flyway rebuilds the schema from its migrations (so
 * the Link Store is empty) and the Short Code script is emptied. Tests therefore never depend on
 * each other or on running order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.ScriptedCodes.class)
abstract class IntegrationTest {

  static final String BASE_URL = "http://sho.rt";
  static final Path DATABASE = TestDatabases.newFile();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvcTester mvc;

  @Autowired ScriptedShortCodeGenerator shortCodes;

  @Autowired private Flyway flyway;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("shortener.base-url", () -> BASE_URL);
    registry.add("shortener.database-path", DATABASE::toString);
    // Tests only: lets each test rebuild the schema from the migrations.
    registry.add("spring.flyway.clean-disabled", () -> "false");
  }

  @BeforeEach
  void startFromAnEmptyLinkStoreAndScript() {
    flyway.clean();
    flyway.migrate();
    shortCodes.willReturn();
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
  static class ScriptedCodes {
    @Bean
    @Primary
    ScriptedShortCodeGenerator scriptedShortCodeGenerator() {
      return new ScriptedShortCodeGenerator();
    }
  }
}
