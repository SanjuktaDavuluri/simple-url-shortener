package io.github.sanjuktadavuluri.shortener;

import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the Click Recorder's stats as metrics (ADR 0015, spec 0006). The Click Recorder knows
 * nothing of Micrometer; this binder reads its stats and queue capacity on each scrape.
 */
@Configuration
class ClicksMetrics {

  @Bean
  MeterBinder clickRecorderMetrics(QueuedClickRecorder recorder) {
    return registry -> {
      FunctionCounter.builder("shortener.clicks.recorded", recorder, r -> r.stats().recorded())
          .register(registry);
      FunctionCounter.builder("shortener.clicks.dropped", recorder, r -> r.stats().dropped())
          .register(registry);
      Gauge.builder("shortener.clicks.pending", recorder, r -> r.stats().pending())
          .register(registry);
      Gauge.builder("shortener.clicks.queue.capacity", recorder, QueuedClickRecorder::queueCapacity)
          .register(registry);
    };
  }
}
