package io.github.sanjuktadavuluri.shortener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Base for integration tests at the HTTP seam (plan 0001): the full Spring application, a fresh
 * SQLite file migrated by Flyway, and a scripted Short Code generator.
 *
 * <p>Spring caches one application context for every class that extends this base, so they share
 * one database file, created once per test run. Tests use distinct Short Codes so they never
 * interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.ScriptedCodes.class)
abstract class IntegrationTest {

  static final String BASE_URL = "http://sho.rt";

  static final Path DATABASE = newDatabaseFile();

  @Autowired MockMvcTester mvc;

  @Autowired ScriptedShortCodeGenerator shortCodes;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("shortener.base-url", () -> BASE_URL);
    registry.add("shortener.database-path", DATABASE::toString);
  }

  private static Path newDatabaseFile() {
    try {
      Path directory = Files.createTempDirectory("shortener-it-");
      directory.toFile().deleteOnExit();
      return directory.resolve("links.db");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
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
