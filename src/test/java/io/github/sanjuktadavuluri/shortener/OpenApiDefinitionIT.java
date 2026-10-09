package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Ticket #185 (spec 0008): the OpenAPI definition of creating a Link is generated from the code,
 * and the running service exposes neither the definition nor a documentation UI. Run with {@code
 * -Dopenapi.write=true} (scripts/openapi.sh) to rewrite {@code docs/api/openapi.yaml}.
 */
class OpenApiDefinitionIT extends IntegrationTest {

  static final Path COMMITTED = Path.of("docs/api/openapi.yaml");

  @Test
  void thePublicPortExposesNoApiDocsAndNoDocumentationUi() {
    for (String path :
        new String[] {
          "/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html"
        }) {
      assertThat(getFromPublicPort(path).statusCode()).as(path).isEqualTo(404);
    }
  }

  @Test
  void theGeneratedDefinitionDescribesCreatingALinkAndIsDeterministic() throws Exception {
    String first = generate();
    String second = generate();

    assertThat(second).isEqualTo(first);
    assertThat(first)
        .contains("openapi: 3.1.0", "/links:", "post:", "\"201\"", "\"400\"", "\"422\"", "\"503\"")
        .contains("manage_token", "expires_in_days", "no-store", "example.com")
        .doesNotContain("\r", "localhost", "/stats", "actuator", "http://localhost");
    assertThat(unfolded(first)).contains("never in a url", "cannot be recovered");
    if (Boolean.getBoolean("openapi.write")) {
      Files.createDirectories(COMMITTED.getParent());
      Files.writeString(COMMITTED, first, StandardCharsets.UTF_8);
    }
  }

  /** The YAML with its line folding undone and whitespace collapsed, lower-cased. */
  private static String unfolded(String yaml) {
    return yaml.replaceAll("\\\\\\s*\n\\s*\\\\", "")
        .replaceAll("\\s+", " ")
        .toLowerCase(java.util.Locale.ROOT);
  }

  /** Starts a temporary instance with the api-docs on and returns its normalised definition. */
  private static String generate() throws IOException, InterruptedException {
    try (ConfigurableApplicationContext app =
        TestApps.start(TestDatabases.newFile(), "--springdoc.api-docs.enabled=true")) {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create(
                              "http://localhost:" + TestApps.port(app) + "/v3/api-docs.yaml"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertThat(response.statusCode()).isEqualTo(200);
      String body = response.body().replace("\r\n", "\n");
      return body.endsWith("\n") ? body : body + "\n";
    }
  }
}
