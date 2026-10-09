package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue #120 (spec 0006 stories 14–17, seam 2; ADR 0015): domain and Click metrics on the
 * management port's Prometheus endpoint. Expected values come from the spec; the registry is shared
 * between tests, so each is observed as a change.
 */
class DomainMetricsIT extends IntegrationTest {

  @Autowired private QueuedClickRecorder recorder;

  private String scrape() {
    return getFromManagementPort("/actuator/prometheus").body();
  }

  private static double value(String body, String series) {
    Matcher m =
        Pattern.compile("^" + Pattern.quote(series) + " (\\S+)$", Pattern.MULTILINE).matcher(body);
    return m.find() ? Double.parseDouble(m.group(1)) : 0;
  }

  private static double delta(String before, String after, String series) {
    return value(after, series) - value(before, series);
  }

  @Test
  void aScriptedSequenceMovesTheDomainCounters() {
    shortCodes.willReturn("Ab3xK9q", "Ab3xK9q", "Zz9yX8w");
    createLink("https://first.test/a");
    String before = scrape();

    assertThat(postLink("http://sho.rt/anything")).hasStatus(422);
    createLink("https://second.test/b"); // draws Ab3xK9q (taken), then Zz9yX8w
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);
    assertThat(mvc.get().uri("/Nope123")).hasStatus(404);
    String after = scrape();

    assertThat(delta(before, after, "shortener_links_total")).isEqualTo(1);
    assertThat(delta(before, after, "shortener_rejections_total{rule=\"self_link\"}")).isEqualTo(1);
    assertThat(delta(before, after, "shortener_collisions_total")).isEqualTo(1);
    assertThat(delta(before, after, "shortener_redirects_total{outcome=\"found\"}")).isEqualTo(2);
    assertThat(delta(before, after, "shortener_redirects_total{outcome=\"not_found\"}"))
        .isEqualTo(1);
  }

  @Test
  void aHeadRequestDoesNotChangeRedirects() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://first.test/a");
    String before = scrape();

    assertThat(mvc.head().uri("/Ab3xK9q")).hasStatus(302);
    assertThat(mvc.head().uri("/Nope123")).hasStatus(404);

    String after = scrape();
    assertThat(delta(before, after, "shortener_redirects_total{outcome=\"found\"}")).isZero();
    assertThat(delta(before, after, "shortener_redirects_total{outcome=\"not_found\"}")).isZero();
  }

  @Test
  void clickMetricsMatchTheRecordersStatsAndTheConfiguredCapacity() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://first.test/a");
    mvc.get().uri("/Ab3xK9q").exchange();
    flushClicks();

    var stats = recorder.stats();
    String body = scrape();
    assertThat(value(body, "shortener_clicks_recorded_total")).isEqualTo(stats.recorded());
    assertThat(value(body, "shortener_clicks_dropped_total")).isEqualTo(stats.dropped());
    assertThat(value(body, "shortener_clicks_pending")).isEqualTo(stats.pending());
    assertThat(value(body, "shortener_clicks_queue_capacity")).isEqualTo(recorder.queueCapacity());
  }

  @Test
  void standardMetricsAreListedWithTheRoutePatternAsTheUri() {
    mvc.get().uri("/Nope123").exchange();

    String body = scrape();

    assertThat(body).contains("http_server_requests_seconds", "uri=\"/{shortCode:");
    assertThat(body).contains("jvm_memory_used_bytes", "hikaricp_connections");
  }

  @Test
  void noShortenerSeriesCarriesAShortCodeLongUrlHostOrReason() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://first.test/secret-path");
    postLink("http://sho.rt/anything");
    mvc.get().uri("/Ab3xK9q").exchange();

    for (String line : scrape().lines().filter(l -> l.startsWith("shortener_")).toList()) {
      assertThat(line)
          .doesNotContain("Ab3xK9q", "first.test", "sho.rt", "secret-path", "aren't allowed");
    }
  }

  @Test
  void theClickRecorderPackageHasNoMicrometerImport() throws Exception {
    Path clicks = Path.of("src/main/java/io/github/sanjuktadavuluri/shortener/clicks");
    try (var files = Files.list(clicks)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        assertThat(Files.readString(file)).doesNotContain("io.micrometer");
      }
    }
  }
}
