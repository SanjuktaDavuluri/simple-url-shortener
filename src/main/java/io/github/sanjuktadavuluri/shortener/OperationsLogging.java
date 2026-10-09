package io.github.sanjuktadavuluri.shortener;

import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import io.github.sanjuktadavuluri.shortener.clicks.StopCounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The operator-facing lines at both ends of the app's life (spec 0006 stories 6, 32 and 37).
 *
 * <p>Once ready, one {@code INFO} line lists the effective non-secret settings. Nothing secret is
 * configured today; any future secret setting must be left out of it.
 *
 * <p>When the context starts closing, Readiness goes {@code REFUSING_TRAFFIC} ({@code
 * OUT_OF_SERVICE} on the management port) and {@code Shutdown started} is logged, before the public
 * server stops. This component also stops in a phase below the Click Recorder's, so {@code Shutdown
 * complete} follows the flush and carries the Clicks flushed and dropped by it (ADR 0012), and
 * comes before the database pool closes.
 */
@Component
class OperationsLogging implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OperationsLogging.class);

  private final ApplicationContext context;
  private final Environment environment;
  private final ShortenerProperties properties;

  private volatile boolean running;

  OperationsLogging(
      ApplicationContext context, Environment environment, ShortenerProperties properties) {
    this.context = context;
    this.environment = environment;
    this.properties = properties;
  }

  @EventListener
  void onReady(ApplicationReadyEvent event) {
    ShortenerProperties.Clicks clicks = properties.clicks();
    String port =
        environment.getProperty("local.server.port", environment.getProperty("server.port"));
    String managementPort =
        environment.getProperty(
            "local.management.port", environment.getProperty("management.server.port"));
    String shutdownTimeout = environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase");
    String logFormat =
        environment.getProperty(LogFormatEnvironmentPostProcessor.VARIABLE, "json").trim();
    log.atInfo()
        .addKeyValue("port", port)
        .addKeyValue("management_port", managementPort)
        .addKeyValue("base_url", properties.baseUrl())
        .addKeyValue("database_path", properties.databasePath())
        .addKeyValue("click_queue_capacity", clicks.queueCapacity())
        .addKeyValue("click_batch_size", clicks.batchSize())
        .addKeyValue("click_flush_interval", clicks.flushInterval().toString())
        .addKeyValue("shutdown_timeout", shutdownTimeout)
        .addKeyValue("click_shutdown_timeout", clicks.shutdownTimeout().toString())
        .addKeyValue("log_format", logFormat)
        .log("Startup settings");
  }

  @EventListener
  void onClosing(ContextClosedEvent event) {
    if (event.getApplicationContext() != context) {
      return;
    }
    AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
    log.info("Shutdown started");
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void stop() {
    running = false;
    StopCounts counts = recorder().lastStop();
    log.atInfo()
        .addKeyValue("clicks_flushed", counts.flushed())
        .addKeyValue("clicks_dropped", counts.dropped())
        .log("Shutdown complete");
  }

  /**
   * Looked up when needed, never injected: a bean that depends on the recorder is stopped before
   * it, and this one must stop after it.
   */
  private QueuedClickRecorder recorder() {
    return context.getBean(QueuedClickRecorder.class);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** One below the Click Recorder's phase: it stops after the recorder, before the pool closes. */
  @Override
  public int getPhase() {
    return recorder().getPhase() - 1;
  }
}
