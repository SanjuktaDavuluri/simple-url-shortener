package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Issue #3: a real HTTP client against a real server port receives the Redirect exactly as
 * specified (plan 0001: MockMvc cannot show what the servlet container actually sends).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(IntegrationTest.ScriptedCodes.class)
class RedirectOverHttpIT {

  @LocalServerPort int port;

  @Autowired ScriptedShortCodeGenerator shortCodes;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    Path database = Files.createTempDirectory("shortener-http-it-").resolve("links.db");
    registry.add("shortener.database-path", database::toString);
  }

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @Test
  void aRealHttpClientReceivesTheRedirectAsSpecified() throws Exception {
    shortCodes.willReturn("H77pRdr");
    HttpResponse<String> created =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/links"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\": \"https://example.com/x\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(created.statusCode()).isEqualTo(201);

    HttpResponse<Void> redirect =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/H77pRdr")).build(),
            HttpResponse.BodyHandlers.discarding());

    assertThat(redirect.statusCode()).isEqualTo(302);
    assertThat(redirect.headers().firstValue("Location")).hasValue("https://example.com/x");
    assertThat(redirect.headers().firstValue("Cache-Control")).hasValue("no-store");
  }
}
