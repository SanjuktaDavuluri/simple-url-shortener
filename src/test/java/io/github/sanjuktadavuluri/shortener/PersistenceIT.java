package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Issue #3: Links survive a restart of the application on the same database file. */
class PersistenceIT extends IntegrationTest {

  @Test
  void linksSurviveARestart() {
    shortCodes.willReturn("Pers1st");
    assertThat(
            mvc.post()
                .uri("/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"https://example.com/kept\"}"))
        .hasStatus(201);

    try (ConfigurableApplicationContext restarted = startAnotherInstanceOnTheSameDatabase()) {
      MockMvcTester afterRestart =
          MockMvcTester.create(
              MockMvcBuilders.webAppContextSetup((WebApplicationContext) restarted).build());

      assertThat(afterRestart.get().uri("/Pers1st"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/kept");
    }
  }

  private static ConfigurableApplicationContext startAnotherInstanceOnTheSameDatabase() {
    return new SpringApplicationBuilder(SimpleUrlShortenerApplication.class)
        .run(
            "--server.port=0",
            "--shortener.base-url=" + BASE_URL,
            "--shortener.database-path=" + DATABASE);
  }
}
