package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Issue #3: production configuration, with the real Short Code generator. */
class ConfigurationIT {

  @TempDir Path tempDir;

  @Test
  void shortUrlsDefaultToLocalhostWithRealShortCodes() throws Exception {
    try (ConfigurableApplicationContext app =
        new SpringApplicationBuilder(SimpleUrlShortenerApplication.class)
            .run("--server.port=0", "--shortener.database-path=" + tempDir.resolve("links.db"))) {
      MockMvcTester mvc =
          MockMvcTester.create(
              MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build());

      assertThat(
              mvc.post()
                  .uri("/links")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"url\": \"https://example.com\"}"))
          .hasStatus(201)
          .bodyJson()
          .extractingPath("$.short_url")
          .asString()
          .matches("http://localhost:8000/[A-Za-z0-9]{7}");
    }
    assertThat(Files.exists(tempDir.resolve("links.db"))).isTrue();
  }
}
