package io.github.sanjuktadavuluri.shortener;

import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The {@code clickQueue} component of Readiness (ADR 0015, spec 0006): {@code OUT_OF_SERVICE} while
 * the Click Recorder's queue is saturated, that is while its pending Clicks have reached the queue
 * capacity and new Clicks are being dropped, and {@code UP} otherwise.
 *
 * <p>It only reads the recorder's running counts: it never waits for the writer and never touches
 * the database. The Click Recorder itself knows nothing of health checks.
 */
@Component
class ClickQueueHealthIndicator implements HealthIndicator {

  private final QueuedClickRecorder clickRecorder;

  ClickQueueHealthIndicator(QueuedClickRecorder clickRecorder) {
    this.clickRecorder = clickRecorder;
  }

  @Override
  public Health health() {
    boolean saturated = clickRecorder.stats().pending() >= clickRecorder.queueCapacity();
    return saturated ? Health.outOfService().build() : Health.up().build();
  }
}
