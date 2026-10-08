package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #50 (spec 0003, ADR 0021): SQLite runs in WAL mode with a 5000 ms busy timeout on every
 * pooled connection, and the pool holds 4 connections.
 *
 * <p>Issue #54 (spec 0003 story 22, Testing Decisions: Concurrency; ADR 0021 Consequences): while a
 * Click batch holds SQLite's write lock, a Redirect still completes, and Link creation waits for
 * the lock instead of failing. A stand-in Click Store holds the batch's write transaction open
 * until the test releases it. Each test runs it in its own instance and database file through
 * {@link TestApps}, so the shared test context never sees it (plan 0001 principle 3). Threads are
 * coordinated with latches, never with sleeps.
 */
class SqliteConcurrencyIT extends IntegrationTest {

  private static final String FIRST_LONG_URL = "https://example.com/first";

  private static final String SECOND_LONG_URL = "https://example.com/second";

  @Autowired private DataSource dataSource;

  @Test
  void thePoolHoldsFourConnectionsEachInWalModeWithAFiveSecondBusyTimeout() throws SQLException {
    HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
    assertThat(pool.getMaximumPoolSize()).isEqualTo(4);

    List<Connection> connections = new ArrayList<>();
    try {
      for (int i = 0; i < 4; i++) {
        connections.add(dataSource.getConnection());
      }
      for (Connection connection : connections) {
        assertThat(pragma(connection, "journal_mode")).isEqualTo("wal");
        assertThat(pragma(connection, "busy_timeout")).isEqualTo("5000");
      }
    } finally {
      for (Connection connection : connections) {
        connection.close();
      }
    }
  }

  @Test
  void aRelease1DatabaseComesUpInWalModeAndStillRedirectsItsLinks() throws SQLException {
    Path release1 = TestDatabases.newFile();
    String url = "jdbc:sqlite:" + release1;
    // A Release 1 database: the V1 schema migrated with the driver's defaults, holding a Link.
    Flyway.configure()
        .dataSource(url, null, null)
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO links (short_code, long_url) VALUES ('Old1234', 'https://example.com/r1')");
      assertThat(pragma(connection, "journal_mode")).isEqualTo("delete");
    }

    try (ConfigurableApplicationContext app =
        TestApps.start(release1, "--shortener.base-url=" + BASE_URL)) {
      try (Connection connection = app.getBean(DataSource.class).getConnection()) {
        assertThat(pragma(connection, "journal_mode")).isEqualTo("wal");
      }
      assertThat(TestApps.mvc(app).get().uri("/Old1234"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/r1");
    }
  }

  @Test
  void aRedirectCompletesWhileAClickBatchHoldsTheWriteLock() throws InterruptedException {
    try (ConfigurableApplicationContext app = startWithAHeldClickBatch()) {
      HoldingClickStore clickStore = app.getBean(HoldingClickStore.class);
      MockMvcTester mvc = TestApps.mvc(app);
      try {
        holdTheWriteLockWithAClickBatch(app, clickStore);

        assertThat(inBackground(() -> mvc.get().uri("/Ab3xK9q").exchange()))
            .succeedsWithin(RedirectNeverWaitsForClicksIT.PROMPTLY)
            .satisfies(
                redirect ->
                    assertThat(redirect)
                        .hasStatus(302)
                        .hasHeader("Location", FIRST_LONG_URL)
                        .hasHeader("Cache-Control", "no-store"));
        assertThat(clickStore.isHolding()).isTrue();
      } finally {
        clickStore.release();
      }
    }
  }

  @Test
  void linkCreationWaitsForTheClickWritersLockAndSucceedsOnceItIsReleased()
      throws InterruptedException {
    try (ConfigurableApplicationContext app = startWithAHeldClickBatch()) {
      HoldingClickStore clickStore = app.getBean(HoldingClickStore.class);
      ScriptedShortCodeGenerator shortCodes = app.getBean(ScriptedShortCodeGenerator.class);
      MockMvcTester mvc = TestApps.mvc(app);
      CompletableFuture<MvcTestResult> creation;
      try {
        holdTheWriteLockWithAClickBatch(app, clickStore);

        shortCodes.willReturn("Zz9yX8w");
        creation = new CompletableFuture<>();
        Thread creator = inBackground(creation, () -> postLink(mvc, SECOND_LONG_URL));
        // Link creation has drawn its Short Code and is saving the Link inside SQLite. That needs
        // the write lock the Click batch holds, so it is waiting and can't finish yet.
        assertThat(shortCodes.awaitDraw(RedirectNeverWaitsForClicksIT.PROMPTLY))
            .isEqualTo("Zz9yX8w");
        assertThat(awaitRunningAStatementInSqlite(creator))
            .as("Link creation is inside SQLite, waiting for the write lock")
            .isTrue();
        assertThat(creation).isNotDone();
      } finally {
        clickStore.release();
      }

      // Released well within the 5000 ms busy timeout: no SQLITE_BUSY, the Link is created.
      assertThat(creation)
          .succeedsWithin(RedirectNeverWaitsForClicksIT.PROMPTLY)
          .satisfies(
              created ->
                  assertThat(created)
                      .hasStatus(201)
                      .bodyJson()
                      .extractingPath("$.short_code")
                      .isEqualTo("Zz9yX8w"));
      assertThat(mvc.get().uri("/Zz9yX8w")).hasStatus(302).hasHeader("Location", SECOND_LONG_URL);
    }
  }

  /** An instance whose Click writer saves each Click as its own batch, through the stand-in. */
  private static ConfigurableApplicationContext startWithAHeldClickBatch() {
    return TestApps.startWithConfigurations(
        TestDatabases.newFile(),
        List.of(IntegrationTest.ScriptedCodes.class, HeldClickBatch.class),
        "--shortener.base-url=" + BASE_URL,
        "--shortener.clicks.batch-size=1");
  }

  /**
   * Creates the Link {@code Ab3xK9q} and Redirects it once. The Click writer takes that Click as a
   * batch, and the stand-in holds the batch's write transaction open.
   */
  private static void holdTheWriteLockWithAClickBatch(
      ConfigurableApplicationContext app, HoldingClickStore clickStore)
      throws InterruptedException {
    MockMvcTester mvc = TestApps.mvc(app);
    app.getBean(ScriptedShortCodeGenerator.class).willReturn("Ab3xK9q");
    assertThat(postLink(mvc, FIRST_LONG_URL)).hasStatus(201);
    assertThat(mvc.get().uri("/Ab3xK9q")).hasStatus(302);
    assertThat(clickStore.awaitHolding(RedirectNeverWaitsForClicksIT.PROMPTLY)).isTrue();
  }

  private static MvcTestResult postLink(MockMvcTester mvc, String longUrl) {
    return mvc.post()
        .uri("/links")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"url\": \"" + longUrl + "\"}")
        .exchange();
  }

  private static <T> CompletableFuture<T> inBackground(Supplier<T> work) {
    CompletableFuture<T> result = new CompletableFuture<>();
    inBackground(result, work);
    return result;
  }

  /** Runs the work on a thread of its own, completing the result with it; returns the thread. */
  private static <T> Thread inBackground(CompletableFuture<T> result, Supplier<T> work) {
    return Thread.ofPlatform()
        .daemon()
        .start(
            () -> {
              try {
                result.complete(work.get());
              } catch (RuntimeException | Error e) {
                result.completeExceptionally(e);
              }
            });
  }

  /**
   * Waits, up to {@link RedirectNeverWaitsForClicksIT#PROMPTLY}, until the thread is running a
   * statement inside SQLite (the SQLite driver's native {@code step}), and says whether it got
   * there. A statement held back by another connection's write lock stays there, waiting out the
   * busy timeout, so this tells a waiting writer apart from one that hasn't reached SQLite yet. The
   * pool opens connections on its own thread, so a statement here is never a new connection's
   * setup.
   */
  private static boolean awaitRunningAStatementInSqlite(Thread thread) {
    long deadline = System.nanoTime() + RedirectNeverWaitsForClicksIT.PROMPTLY.toNanos();
    while (System.nanoTime() < deadline) {
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().equals("org.sqlite.core.NativeDB")
            && frame.getMethodName().equals("step")) {
          return true;
        }
      }
      Thread.onSpinWait();
    }
    return false;
  }

  /**
   * A stand-in Click Store. It saves the first batch through the real Click Store inside a write
   * transaction, then holds that transaction (and with it SQLite's write lock) open until released.
   * Later batches go straight through.
   */
  static final class HoldingClickStore implements ClickStore {

    private final ClickStore clickStore;
    private final TransactionTemplate transaction;
    private final CountDownLatch holding = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    HoldingClickStore(ClickStore clickStore, PlatformTransactionManager transactionManager) {
      this.clickStore = clickStore;
      this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void saveAll(List<Click> clicks) {
      transaction.executeWithoutResult(
          status -> {
            // The real store joins this transaction: once the batch is written, the lock is held.
            clickStore.saveAll(clicks);
            holding.countDown();
            try {
              released.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    @Override
    public List<Click> listClicks(String shortCode) {
      return clickStore.listClicks(shortCode);
    }

    boolean awaitHolding(Duration timeout) throws InterruptedException {
      return holding.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Whether a batch's write transaction is open and not yet released. */
    boolean isHolding() {
      return holding.getCount() == 0 && released.getCount() == 1;
    }

    void release() {
      released.countDown();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class HeldClickBatch {
    @Bean
    @Primary
    HoldingClickStore holdingClickStore(
        @Qualifier("jdbcClickStore") ClickStore clickStore,
        PlatformTransactionManager transactionManager) {
      return new HoldingClickStore(clickStore, transactionManager);
    }
  }

  private static String pragma(Connection connection, String name) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA " + name)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }
}
