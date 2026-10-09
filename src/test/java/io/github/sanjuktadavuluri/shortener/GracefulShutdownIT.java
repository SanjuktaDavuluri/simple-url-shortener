package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Issue #121 (spec 0006 stories 6 and 32–35; ADR 0012): a stop is safe and bounded. Readiness goes
 * {@code OUT_OF_SERVICE} as the close begins, the public server refuses new connections while
 * in-flight requests finish, and the close never waits longer than {@code SHUTDOWN_TIMEOUT} for a
 * stuck request. A stand-in handler (plan 0001 principle 2) holds a request in flight until the
 * test releases it; threads are coordinated with latches and bounded futures, never fixed sleeps.
 */
@ExtendWith(OutputCaptureExtension.class)
class GracefulShutdownIT {

  private static final Duration BOUND = Duration.ofSeconds(30);

  @Test
  void readinessIsOutOfServiceOnceTheContextStartsClosingWhileTheServerDrains() {
    try (SlowApp slow = SlowApp.start()) {
      CompletableFuture<HttpResponse<String>> inFlight = slow.requestInFlight();
      CompletableFuture<Void> closing = slow.closeInBackground();

      HttpResponse<String> readiness = slow.awaitReadinessOutOfService();

      assertThat(readiness.statusCode()).isEqualTo(503);
      assertThat(PlainHttp.json(readiness)).containsEntry("status", "OUT_OF_SERVICE");
      slow.release();
      assertThat(inFlight).succeedsWithin(BOUND);
      assertThat(closing).succeedsWithin(BOUND);
    }
  }

  @Test
  void aRequestInFlightWhenTheCloseBeginsCompletesAndANewConnectionIsRefused() {
    try (SlowApp slow = SlowApp.start()) {
      CompletableFuture<HttpResponse<String>> inFlight = slow.requestInFlight();
      CompletableFuture<Void> closing = slow.closeInBackground();
      slow.awaitReadinessOutOfService();

      assertThat(slow.awaitNewConnectionRefused()).isTrue();
      assertThat(inFlight).isNotDone();

      slow.release();
      assertThat(inFlight)
          .succeedsWithin(BOUND)
          .satisfies(
              response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo(SlowController.BODY);
              });
      assertThat(closing).succeedsWithin(BOUND);
    }
  }

  @Test
  void aShutdownTimeoutShorterThanTheHandlerEndsTheCloseWithinTheBound() {
    try (SlowApp slow = SlowApp.start("--SHUTDOWN_TIMEOUT=1s")) {
      slow.requestInFlight();

      long started = System.nanoTime();
      CompletableFuture<Void> closing = slow.closeInBackground();

      // The handler is still held: only the timeout can end the close.
      assertThat(closing).succeedsWithin(Duration.ofSeconds(10));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
      slow.release();
    }
  }

  @Test
  void aRedirectServedDuringTheShutdownStillHasItsClickSaved() {
    Path database = TestDatabases.newFile();
    String shortCode;
    try (SlowApp slow = SlowApp.start(database, "--shortener.clicks.flush-interval=10m")) {
      shortCode = ClickShutdownIT.createLink(slow.app, "https://example.test/served-while-closing");
      CompletableFuture<HttpResponse<String>> inFlight = slow.requestInFlight();
      CompletableFuture<Void> closing = slow.closeInBackground();
      slow.awaitReadinessOutOfService();

      // The Redirect is served by the draining server, after the close began.
      // (Through the application's dispatcher: Tomcat already refuses new connections.)
      assertThat(TestApps.mvc(slow.app).get().uri("/" + shortCode)).hasStatus(302);
      slow.release();
      assertThat(inFlight).succeedsWithin(BOUND);
      assertThat(closing).succeedsWithin(BOUND);
    }

    try (ConfigurableApplicationContext restarted = TestApps.start(database)) {
      assertThat(restarted.getBean(ClickStore.class).listClicks(shortCode))
          .extracting(Click::shortCode)
          .containsExactly(shortCode);
    }
  }

  @Test
  void theLogsHaveShutdownStartedAndShutdownCompleteLinesWithTheClicksFlushedAndDropped(
      CapturedOutput out) {
    try (SlowApp slow = SlowApp.start("--shortener.clicks.flush-interval=10m")) {
      String shortCode = ClickShutdownIT.createLink(slow.app, "https://example.test/logged");
      assertThat(TestApps.mvc(slow.app).get().uri("/" + shortCode)).hasStatus(302);
      assertThat(TestApps.mvc(slow.app).get().uri("/" + shortCode)).hasStatus(302);
      slow.closeInBackground();
      slow.awaitClosed();
    }

    List<JsonNode> lines = LogLines.parseJsonLines(out);
    assertThat(lines)
        .filteredOn(line -> "Shutdown started".equals(LogLines.text(line, "message")))
        .hasSize(1);
    assertThat(lines)
        .filteredOn(line -> "Shutdown complete".equals(LogLines.text(line, "message")))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(LogLines.text(line, "clicks_flushed")).isEqualTo("2");
              assertThat(LogLines.text(line, "clicks_dropped")).isEqualTo("0");
            });
  }

  /** A running instance with the slow stand-in handler, closed in the background by the test. */
  private static final class SlowApp implements AutoCloseable {

    final ConfigurableApplicationContext app;
    private CompletableFuture<Void> closing;

    private SlowApp(ConfigurableApplicationContext app) {
      this.app = app;
    }

    static SlowApp start(String... arguments) {
      return start(TestDatabases.newFile(), arguments);
    }

    static SlowApp start(Path database, String... arguments) {
      SlowController.reset();
      return new SlowApp(
          TestApps.startWithConfigurations(database, List.of(SlowHandler.class), arguments));
    }

    CompletableFuture<HttpResponse<String>> requestInFlight() {
      int port = TestApps.port(app);
      CompletableFuture<HttpResponse<String>> response =
          CompletableFuture.supplyAsync(() -> PlainHttp.get(port, SlowController.PATH));
      try {
        assertThat(SlowController.entered.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return response;
    }

    CompletableFuture<Void> closeInBackground() {
      closing = CompletableFuture.runAsync(app::close);
      return closing;
    }

    void awaitClosed() {
      assertThat(closing).succeedsWithin(BOUND);
    }

    void release() {
      SlowController.release.countDown();
    }

    HttpResponse<String> awaitReadinessOutOfService() {
      long deadline = System.nanoTime() + BOUND.toNanos();
      int managementPort = TestApps.managementPort(app);
      while (System.nanoTime() < deadline) {
        try {
          HttpResponse<String> readiness =
              PlainHttp.get(managementPort, "/actuator/health/readiness");
          if (readiness.statusCode() == 503) {
            return readiness;
          }
        } catch (RuntimeException e) {
          // The management server is not answering (yet): try again until the bound.
        }
        Thread.onSpinWait();
      }
      throw new AssertionError("Readiness never reported OUT_OF_SERVICE");
    }

    boolean awaitNewConnectionRefused() {
      long deadline = System.nanoTime() + BOUND.toNanos();
      int port = TestApps.port(app);
      while (System.nanoTime() < deadline) {
        try (Socket socket = new Socket()) {
          socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (IOException e) {
          return true;
        }
        Thread.onSpinWait();
      }
      return false;
    }

    @Override
    public void close() {
      release();
      app.close();
    }
  }

  /** A stand-in handler that holds its request until the test releases it. */
  @RestController
  static final class SlowController {

    static final String PATH = "/stand-in/slow";
    static final String BODY = "slow done";

    static volatile CountDownLatch entered = new CountDownLatch(1);
    static volatile CountDownLatch release = new CountDownLatch(1);

    static void reset() {
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }

    @GetMapping(PATH)
    String slow() throws InterruptedException {
      entered.countDown();
      release.await(60, TimeUnit.SECONDS);
      return BODY;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SlowHandler {
    @Bean
    SlowController slowController() {
      return new SlowController();
    }
  }
}
