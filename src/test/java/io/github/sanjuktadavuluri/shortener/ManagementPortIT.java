package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #116 (spec 0006 stories 1–3, 7, 10–12; ADR 0015): Liveness and Readiness answer on the
 * management port, which is never the public port, and nothing but health and Prometheus metrics is
 * exposed there. Observed over plain HTTP on both real ports (spec 0006 seams 1 and 2).
 */
class ManagementPortIT extends IntegrationTest {

  @Test
  void livenessAnswersUpOnTheManagementPort() {
    HttpResponse<String> liveness = getFromManagementPort("/actuator/health/liveness");

    assertThat(liveness.statusCode()).isEqualTo(200);
    assertThat(PlainHttp.json(liveness))
        .isEqualTo(
            Map.of("status", "UP", "components", Map.of("livenessState", Map.of("status", "UP"))));
  }

  @Test
  void readinessAnswersUpAndNamesTheDatabaseTheClickQueueAndTheReadinessStateWithoutDetails() {
    HttpResponse<String> readiness = getFromManagementPort("/actuator/health/readiness");

    assertThat(readiness.statusCode()).isEqualTo(200);
    assertThat(PlainHttp.json(readiness))
        .isEqualTo(
            Map.of(
                "status",
                "UP",
                "components",
                Map.of(
                    "db", Map.of("status", "UP"),
                    "clickQueue", Map.of("status", "UP"),
                    "readinessState", Map.of("status", "UP"))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator",
        "/actuator/health",
        "/actuator/health/readiness",
        "/actuator/prometheus"
      })
  void everyActuatorPathAnswersNotFoundOnThePublicPortLikeAnyUnknownPath(String path) {
    assertThat(getFromPublicPort("/no/such/page").statusCode()).isEqualTo(404);

    assertThat(getFromPublicPort(path).statusCode()).isEqualTo(404);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator/env",
        "/actuator/configprops",
        "/actuator/heapdump",
        "/actuator/shutdown"
      })
  void onlyHealthAndPrometheusAreExposedOnTheManagementPort(String path) {
    assertThat(getFromManagementPort(path).statusCode()).isEqualTo(404);
  }

  @Test
  void theShutdownEndpointCannotBePostedToOnTheManagementPort() {
    assertThat(PlainHttp.send(managementPort, "POST", "/actuator/shutdown").statusCode())
        .isEqualTo(404);
    assertThat(getFromManagementPort("/actuator/health/liveness").statusCode()).isEqualTo(200);
  }

  @Test
  void prometheusMetricsAnswerInPrometheusTextOnTheManagementPort() {
    HttpResponse<String> metrics = getFromManagementPort("/actuator/prometheus");

    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
    assertThat(metrics.body()).contains("# HELP ", "# TYPE ");
  }
}
