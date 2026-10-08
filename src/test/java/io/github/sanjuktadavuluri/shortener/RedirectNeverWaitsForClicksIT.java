package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickRecorder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

/**
 * Issue #54 (spec 0003 stories 2 and 3, Testing Decisions: Redirect never waits or fails): with a
 * stand-in Click Recorder that blocks or throws, swapped in through a {@code @TestConfiguration}
 * like the scripted Short Code generator, the Redirect still returns its {@code 302}.
 *
 * <p>Each test starts its own instance on its own database file through {@link TestApps}, so the
 * stand-in never reaches the shared test context (plan 0001 principle 3).
 */
class RedirectNeverWaitsForClicksIT {

  /**
   * How long a Redirect may take here. A bound, not a sleep: the test goes on as soon as the
   * Redirect arrives, and fails only if it doesn't arrive in time.
   */
  static final Duration PROMPTLY = Duration.ofSeconds(3);

  @Test
  void aClickRecorderThatBlocksDoesNotHoldBackTheRedirect() throws Exception {
    try (ConfigurableApplicationContext app = start(BlockingRecorder.class);
        HttpClient http =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
      BlockingClickRecorder recorder = app.getBean(BlockingClickRecorder.class);
      try {
        createLink(app, "Ab3xK9q", "https://example.com/very/long");

        // A real HTTP client: MockMvc can't show the visitor getting the 302 before the handler
        // thread is done with the Click.
        assertThat(
                http.sendAsync(
                    HttpRequest.newBuilder(
                            URI.create("http://localhost:" + TestApps.port(app) + "/Ab3xK9q"))
                        .build(),
                    HttpResponse.BodyHandlers.discarding()))
            .succeedsWithin(PROMPTLY)
            .satisfies(
                redirect -> {
                  assertThat(redirect.statusCode()).isEqualTo(302);
                  assertThat(redirect.headers().firstValue("Location"))
                      .hasValue("https://example.com/very/long");
                  assertThat(redirect.headers().firstValue("Cache-Control")).hasValue("no-store");
                });
        // The Click was handed over, and the recorder is still holding on to it.
        assertThat(recorder.isBlocked()).isTrue();
      } finally {
        recorder.release();
      }
    }
  }

  @Test
  void aClickRecorderThatThrowsDoesNotBreakTheRedirect() {
    try (ConfigurableApplicationContext app = start(ThrowingRecorder.class)) {
      createLink(app, "Ab3xK9q", "https://example.com/very/long");

      assertThat(TestApps.mvc(app).get().uri("/Ab3xK9q"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/very/long")
          .hasHeader("Cache-Control", "no-store");
      assertThat(app.getBean(ThrowingClickRecorder.class).calls()).isEqualTo(1);
    }
  }

  private static ConfigurableApplicationContext start(Class<?> standInRecorder) {
    return TestApps.startWithConfigurations(
        TestDatabases.newFile(),
        List.of(IntegrationTest.ScriptedCodes.class, standInRecorder),
        "--shortener.base-url=" + IntegrationTest.BASE_URL);
  }

  private static void createLink(ConfigurableApplicationContext app, String shortCode, String url) {
    app.getBean(ScriptedShortCodeGenerator.class).willReturn(shortCode);
    assertThat(
            TestApps.mvc(app)
                .post()
                .uri("/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"" + url + "\"}"))
        .hasStatus(201);
  }

  /** Breaks the Click Recorder's "returns immediately" promise: never returns until released. */
  static final class BlockingClickRecorder implements ClickRecorder {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    @Override
    public void record(Click click) {
      entered.countDown();
      try {
        released.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    /** Whether a Click was handed over and its {@code record} call hasn't returned yet. */
    boolean isBlocked() throws InterruptedException {
      return entered.await(PROMPTLY.toMillis(), TimeUnit.MILLISECONDS) && released.getCount() == 1;
    }

    void release() {
      released.countDown();
    }
  }

  /** Breaks the Click Recorder's "never throws" promise: every {@code record} call throws. */
  static final class ThrowingClickRecorder implements ClickRecorder {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public void record(Click click) {
      calls.incrementAndGet();
      throw new IllegalStateException("The stand-in Click Recorder always fails");
    }

    int calls() {
      return calls.get();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class BlockingRecorder {
    @Bean
    @Primary
    BlockingClickRecorder blockingClickRecorder() {
      return new BlockingClickRecorder();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ThrowingRecorder {
    @Bean
    @Primary
    ThrowingClickRecorder throwingClickRecorder() {
      return new ThrowingClickRecorder();
    }
  }
}
