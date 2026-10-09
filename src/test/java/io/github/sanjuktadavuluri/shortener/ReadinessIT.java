package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.QueuedClickRecorder;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Issue #116 (spec 0006 stories 2–5 and 7; ADR 0015, ADR 0012): Readiness takes an instance out of
 * rotation while the database can't be reached or the Click queue is saturated, and Liveness never
 * depends on either.
 *
 * <p>Each test starts its own instance on its own database file through {@link TestApps}, with a
 * stand-in swapped in for that instance only (plan 0001 principles 2 and 3), and reads health over
 * plain HTTP on its management port (spec 0006 seam 2).
 */
class ReadinessIT {

  private static final Map<String, Object> UP = Map.of("status", "UP");

  @Test
  void whileTheDatabaseIsUnreachableReadinessAnswers503WithTheDatabaseDownAndLivenessStaysUp() {
    try (ConfigurableApplicationContext app =
        TestApps.startWithConfigurations(
            TestDatabases.newFile(), List.of(UnreachableDatabase.class))) {
      assertThat(readiness(app).statusCode()).isEqualTo(200);

      app.getBean(SwitchableDataSource.class).makeUnreachable();

      HttpResponse<String> readiness = readiness(app);
      assertThat(readiness.statusCode()).isEqualTo(503);
      assertThat(PlainHttp.json(readiness))
          .isEqualTo(
              Map.of(
                  "status",
                  "DOWN",
                  "components",
                  Map.of(
                      "db", Map.of("status", "DOWN"),
                      "clickQueue", UP,
                      "readinessState", UP)));
      HttpResponse<String> liveness =
          TestApps.getFromManagementPort(app, "/actuator/health/liveness");
      assertThat(liveness.statusCode()).isEqualTo(200);
      assertThat(PlainHttp.json(liveness))
          .isEqualTo(Map.of("status", "UP", "components", Map.of("livenessState", UP)));
    }
  }

  @Test
  void whileTheClickQueueIsFullReadinessAnswers503AndUpAgainOnceTheQueueDrains() {
    try (ConfigurableApplicationContext app =
        TestApps.startWithConfigurations(
            TestDatabases.newFile(),
            List.of(IntegrationTest.ScriptedCodes.class, BlockedClicks.class),
            "--shortener.base-url=" + IntegrationTest.BASE_URL,
            "--shortener.clicks.queue-capacity=2",
            "--shortener.clicks.batch-size=1")) {
      BlockedClickStore clickStore = app.getBean(BlockedClickStore.class);
      try {
        MockMvcTester mvc = TestApps.mvc(app);
        app.getBean(ScriptedShortCodeGenerator.class).willReturn("Ab3xK9q");
        assertThat(
                mvc.post()
                    .uri("/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\": \"https://example.com/very/long\"}"))
            .hasStatus(201);
        assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);
        assertThat(readiness(app).statusCode()).isEqualTo(200);

        // The Click Store holds on to the first Click, so the second fills the queue's capacity.
        assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);

        HttpResponse<String> saturated = readiness(app);
        assertThat(saturated.statusCode()).isEqualTo(503);
        assertThat(PlainHttp.json(saturated))
            .isEqualTo(
                Map.of(
                    "status",
                    "OUT_OF_SERVICE",
                    "components",
                    Map.of(
                        "db", UP,
                        "clickQueue", Map.of("status", "OUT_OF_SERVICE"),
                        "readinessState", UP)));

        clickStore.release();
        app.getBean(QueuedClickRecorder.class).flush();

        HttpResponse<String> drained = readiness(app);
        assertThat(drained.statusCode()).isEqualTo(200);
        assertThat(PlainHttp.json(drained))
            .isEqualTo(
                Map.of(
                    "status",
                    "UP",
                    "components",
                    Map.of("db", UP, "clickQueue", UP, "readinessState", UP)));
      } finally {
        clickStore.release();
      }
    }
  }

  private static HttpResponse<String> readiness(ConfigurableApplicationContext app) {
    return TestApps.getFromManagementPort(app, "/actuator/health/readiness");
  }

  /**
   * The unreachable-database stand-in: the instance's real {@code DataSource}, until the test makes
   * it unreachable; from then on every new connection fails, as when the database file can't be
   * opened. (Removing or locking the SQLite file isn't enough: the pool keeps its open connections
   * working, so the file approach can't show an outage reliably.)
   */
  static final class SwitchableDataSource extends DelegatingDataSource {

    private volatile boolean unreachable;

    SwitchableDataSource(DataSource real) {
      super(real);
    }

    void makeUnreachable() {
      unreachable = true;
    }

    @Override
    public Connection getConnection() throws SQLException {
      failIfUnreachable();
      return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      failIfUnreachable();
      return super.getConnection(username, password);
    }

    private void failIfUnreachable() throws SQLException {
      if (unreachable) {
        throw new SQLException("The stand-in database is unreachable");
      }
    }
  }

  /**
   * A stand-in Click Store that holds on to every batch until released, then saves it through the
   * real Click Store (the spec 0003 seam).
   */
  static final class BlockedClickStore implements ClickStore {

    private final ClickStore real;
    private final CountDownLatch released = new CountDownLatch(1);

    BlockedClickStore(ClickStore real) {
      this.real = real;
    }

    void release() {
      released.countDown();
    }

    @Override
    public void saveAll(List<Click> clicks) {
      try {
        released.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      real.saveAll(clicks);
    }

    @Override
    public List<Click> listClicks(String shortCode) {
      return real.listClicks(shortCode);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class UnreachableDatabase {
    @Bean
    static BeanPostProcessor switchableDataSource() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          return bean instanceof DataSource real && !(bean instanceof SwitchableDataSource)
              ? new SwitchableDataSource(real)
              : bean;
        }
      };
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class BlockedClicks {
    @Bean
    @Primary
    BlockedClickStore blockedClickStore(@Qualifier("jdbcClickStore") ClickStore real) {
      return new BlockedClickStore(real);
    }
  }
}
