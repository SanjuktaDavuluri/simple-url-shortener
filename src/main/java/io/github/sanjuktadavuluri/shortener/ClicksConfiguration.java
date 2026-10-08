package io.github.sanjuktadavuluri.shortener;

import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts the queued Click Recorder in force (ADR 0012), and the clock that times each Click. */
@Configuration
class ClicksConfiguration {

  /** System UTC. Tests replace it with a fixed clock. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  QueuedClickRecorder clickRecorder(ClickStore store, ShortenerProperties properties) {
    ShortenerProperties.Clicks clicks = properties.clicks();
    return new QueuedClickRecorder(
        store, clicks.queueCapacity(), clicks.batchSize(), clicks.flushInterval());
  }
}
