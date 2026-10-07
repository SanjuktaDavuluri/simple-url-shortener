package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;

/** Issue #3: production configuration, with the real Short Code generator. */
class ConfigurationIT {

  @Test
  void shortUrlsDefaultToLocalhostWithRealShortCodes() {
    try (ConfigurableApplicationContext app = TestApps.start(TestDatabases.newFile())) {
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
          .matches("http://localhost:8000/[A-Za-z0-9]{7}");
    }
  }
}
