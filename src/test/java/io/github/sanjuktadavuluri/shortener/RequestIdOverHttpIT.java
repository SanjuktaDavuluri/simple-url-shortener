package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue #117 (spec 0006 stories 21 and 26, Implementation Decisions: Errors; Testing Decisions seam
 * 1): over a real socket, so the servlet container's own error handling is part of what is tested
 * (MockMvc never forwards to the error page). An unexpected exception answers {@code 500} with an
 * {@code X-Request-Id} and a body that reveals no exception message or stack trace, and the
 * management port is untouched by the Request ID filter.
 *
 * <p>The failing handler is a stand-in on its own {@link TestApps} instance and database file,
 * never in the shared test context (plan 0001 principles 2 and 3).
 */
class RequestIdOverHttpIT {

  static final String HEADER = RequestIdIT.HEADER;

  /** Distinctive, so it can be searched for in the response body. */
  static final String EXCEPTION_MESSAGE = "stand-in failure 7f3c secret internals";

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @Test
  void anUnexpectedExceptionAnswers500WithARequestIdAndNoInternals() throws Exception {
    try (ConfigurableApplicationContext app = startWithFailingHandler()) {
      HttpResponse<String> response = get(TestApps.port(app), FailingController.PATH, null);

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.headers().firstValue(HEADER))
          .hasValueSatisfying(id -> assertThat(id).matches(RequestIdIT.GENERATED));
      assertThat(response.body())
          .doesNotContain(EXCEPTION_MESSAGE)
          .doesNotContain("IllegalStateException")
          .doesNotContain("FailingController")
          .doesNotContainIgnoringCase("trace")
          .doesNotContain("\tat ");
    }
  }

  @Test
  void aSafeIncomingRequestIdIsKeptOnA500() throws Exception {
    try (ConfigurableApplicationContext app = startWithFailingHandler()) {
      HttpResponse<String> response =
          get(TestApps.port(app), FailingController.PATH, "abc-123_DEF.4");

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.headers().firstValue(HEADER)).hasValue("abc-123_DEF.4");
    }
  }

  @Test
  void aPathNoHandlerMatchesStillAnswers404WithARequestId() throws Exception {
    try (ConfigurableApplicationContext app = startWithFailingHandler()) {
      HttpResponse<String> response = get(TestApps.port(app), "/not-a-short-code", null);

      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.headers().firstValue(HEADER)).isPresent();
    }
  }

  /**
   * Health and metrics on the management port are not affected by the filter (spec 0006: it applies
   * only to the public port). The management port arrives with Actuator in #116 (spec 0006 slice
   * 1); until then there is no management port to probe, so this test is skipped, and it runs as
   * soon as Actuator is on the classpath.
   */
  @Test
  @EnabledIf("actuatorIsPresent")
  void healthAndMetricsOnTheManagementPortCarryNoRequestId() throws Exception {
    try (ConfigurableApplicationContext app =
        TestApps.start(TestDatabases.newFile(), "--management.server.port=0")) {
      int managementPort =
          Integer.parseInt(app.getEnvironment().getRequiredProperty("local.management.port"));
      assertThat(managementPort).isNotEqualTo(TestApps.port(app));

      HttpResponse<String> liveness =
          get(managementPort, "/actuator/health/liveness", "abc-123_DEF.4");
      assertThat(liveness.statusCode()).isEqualTo(200);
      assertThat(liveness.headers().firstValue(HEADER)).isEmpty();

      HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus", "abc-123_DEF.4");
      assertThat(metrics.headers().firstValue(HEADER)).isEmpty();

      // The public port of the same instance does carry it.
      assertThat(get(TestApps.port(app), "/", null).headers().firstValue(HEADER)).isPresent();
    }
  }

  static boolean actuatorIsPresent() {
    return ClassUtils.isPresent(
        "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
        RequestIdOverHttpIT.class.getClassLoader());
  }

  private HttpResponse<String> get(int port, String path, String requestId) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Accept", "application/json");
    if (requestId != null) {
      request.header(HEADER, requestId);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static ConfigurableApplicationContext startWithFailingHandler() {
    return TestApps.startWithConfigurations(TestDatabases.newFile(), List.of(FailingHandler.class));
  }

  /** A stand-in handler that always throws an unexpected exception. */
  @RestController
  static final class FailingController {

    static final String PATH = "/stand-in/fail";

    @GetMapping(PATH)
    String fail() {
      throw new IllegalStateException(EXCEPTION_MESSAGE);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FailingHandler {
    @Bean
    FailingController failingController() {
      return new FailingController();
    }
  }
}
