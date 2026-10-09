package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Issue #119 (spec 0006 stories 24–27, seam 3) on instances of their own: a forced {@code 500}, the
 * Click handover warning, and {@code LOG_FORMAT}. Each starts a real instance through {@link
 * TestApps}, never the shared context (plan 0001 principle 3).
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogsOverHttpIT {

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @Test
  void aForced500IsLoggedExactlyOnceAtErrorWithAStackTraceAndTheRequestId(CapturedOutput out)
      throws Exception {
    try (ConfigurableApplicationContext app =
        TestApps.startWithConfigurations(
            TestDatabases.newFile(), List.of(RequestIdOverHttpIT.FailingHandler.class))) {
      HttpResponse<String> response =
          get(TestApps.port(app), RequestIdOverHttpIT.FailingController.PATH);
      String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
      assertThat(response.statusCode()).isEqualTo(500);

      List<JsonNode> errors =
          LogLines.parseJsonLines(out).stream()
              .filter(l -> "ERROR".equals(LogLines.level(l)))
              .toList();
      assertThat(errors).hasSize(1);
      assertThat(LogLines.text(errors.get(0), "request_id")).isEqualTo(requestId);
      assertThat(LogLines.text(errors.get(0), "error.stack_trace")).contains("\tat ");
    }
  }

  @Test
  void theClickHandoverWarningCarriesTheRequestsRequestId(CapturedOutput out) {
    try (ConfigurableApplicationContext app =
        TestApps.startWithConfigurations(
            TestDatabases.newFile(),
            List.of(
                IntegrationTest.ScriptedCodes.class,
                RedirectNeverWaitsForClicksIT.ThrowingRecorder.class),
            "--shortener.base-url=" + IntegrationTest.BASE_URL)) {
      app.getBean(ScriptedShortCodeGenerator.class).willReturn("Ab3xK9q");
      assertThat(
              TestApps.mvc(app)
                  .post()
                  .uri("/links")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"url\": \"https://example.com/very/long\"}"))
          .hasStatus(201);

      String requestId =
          TestApps.mvc(app)
              .get()
              .uri("/Ab3xK9q")
              .exchange()
              .getResponse()
              .getHeader("X-Request-Id");

      List<JsonNode> warnings =
          LogLines.parseJsonLines(out).stream()
              .filter(l -> "WARN".equals(LogLines.level(l)))
              .filter(l -> LogLines.logger(l).endsWith("LinkController"))
              .toList();
      assertThat(warnings).hasSize(1);
      assertThat(LogLines.text(warnings.get(0), "request_id")).isEqualTo(requestId);
    }
  }

  @Test
  void logFormatTextSelectsSpringsPlainPatternAndJsonTheStructuredFormat() {
    // Logback is configured once per JVM, so the lines themselves are checked by running with
    // LOG_FORMAT=text; here the format each value selects is checked on a started instance.
    try (ConfigurableApplicationContext text =
            TestApps.startWithEnvironment(TestDatabases.newFile(), Map.of("LOG_FORMAT", "text"));
        ConfigurableApplicationContext json =
            TestApps.startWithEnvironment(TestDatabases.newFile(), Map.of("LOG_FORMAT", "json"))) {
      assertThat(text.getEnvironment().getProperty("logging.structured.format.console")).isNull();
      assertThat(json.getEnvironment().getProperty("logging.structured.format.console"))
          .isEqualTo("ecs");
    }
  }

  @Test
  void anUnknownLogFormatStopsStartupWithAMessageNamingIt() {
    assertThatThrownBy(
            () ->
                TestApps.startWithEnvironment(
                    TestDatabases.newFile(), Map.of("LOG_FORMAT", "yaml")))
        .hasStackTraceContaining("LOG_FORMAT")
        .hasStackTraceContaining("yaml");
  }

  private HttpResponse<String> get(int port, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
