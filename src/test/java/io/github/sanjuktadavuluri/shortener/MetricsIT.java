package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue #120 (spec 0006 stories 14–17, ADR 0015): the domain and Click metrics on the management
 * port's Prometheus endpoint, read as text over plain HTTP (spec 0006 seam 2). The context is
 * shared, so each test compares the series before and after.
 */
class MetricsIT extends IntegrationTest {

  private static final String CODE = "Ab3xK9q";

  @Autowired QueuedClickRecorder recorder;

  private List<String> scrape() {
    return getFromManagementPort("/actuator/prometheus").body().lines().toList();
  }

  /** The value of the series whose line starts with {@code series}, 0 when absent. */
  private double value(String series) {
    Optional<String> line = scrape().stream().filter(l -> l.startsWith(series + " ")).findFirst();
    return line.map(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).orElse(0.0);
  }

  @Test
  void aScriptedSequenceMovesTheDomainCountersByTheSpecifiedAmounts() {
    double created = value("shortener_links_created_total");
    double selfLink = value("shortener_rejections_total{rule=\"self_link\"");
    double collisions = value("shortener_collisions_total");
    double found = value("shortener_redirects_total{outcome=\"found\"");
    double notFound = value("shortener_redirects_total{outcome=\"not_found\"");
    shortCodes.willReturn(CODE);
    createLink("https://host.test/first");
    postLink("http://sho.rt/anything");
    shortCodes.willReturn(CODE, "Zz9yX8w");
    createLink("https://host.test/second");
    mvc.get().uri("/" + CODE).exchange();
    mvc.get().uri("/" + CODE).exchange();
    mvc.get().uri("/Qq1Qq1Q").exchange();

    assertThat(value("shortener_links_created_total") - created).isEqualTo(2);
    assertThat(value("shortener_rejections_total{rule=\"self_link\"") - selfLink).isEqualTo(1);
    assertThat(value("shortener_collisions_total") - collisions).isEqualTo(1);
    assertThat(value("shortener_redirects_total{outcome=\"found\"") - found).isEqualTo(2);
    assertThat(value("shortener_redirects_total{outcome=\"not_found\"") - notFound).isEqualTo(1);
  }

  @Test
  void aHeadRequestDoesNotChangeTheRedirectCounters() {
    shortCodes.willReturn(CODE);
    createLink("https://host.test/first");
    double found = value("shortener_redirects_total{outcome=\"found\"");

    mvc.head().uri("/" + CODE).exchange();

    assertThat(value("shortener_redirects_total{outcome=\"found\"")).isEqualTo(found);
  }

  @Test
  void theClickSeriesMatchTheRecordersStatsAfterAFlush() {
    shortCodes.willReturn(CODE);
    createLink("https://host.test/first");
    mvc.get().uri("/" + CODE).exchange();
    flushClicks();

    var stats = recorder.stats();

    assertThat(value("shortener_clicks_recorded_total")).isEqualTo(stats.recorded());
    assertThat(value("shortener_clicks_dropped_total")).isEqualTo(stats.dropped());
    assertThat(value("shortener_clicks_pending")).isEqualTo(stats.pending());
    assertThat(value("shortener_clicks_queue_capacity")).isEqualTo(recorder.queueCapacity());
  }

  @Test
  void standardHttpJvmAndPoolMetricsArePresentAndNoShortenerSeriesLeaksAnIdentifier() {
    shortCodes.willReturn(CODE);
    createLink("https://host.test/first");
    getFromPublicPort("/" + CODE);
    List<String> lines = scrape();

    assertThat(lines)
        .anyMatch(l -> l.startsWith("http_server_requests_seconds_count{") && l.contains("uri=\"/{shortCode}\""))
        .anyMatch(l -> l.startsWith("jvm_memory_used_bytes"))
        .anyMatch(l -> l.startsWith("hikaricp_connections"));
    assertThat(lines.stream().filter(l -> l.startsWith("shortener_")))
        .noneMatch(l -> l.contains(CODE) || l.contains("host.test") || l.contains("sho.rt"))
        .noneMatch(l -> l.contains("aren't allowed"));
  }
}
