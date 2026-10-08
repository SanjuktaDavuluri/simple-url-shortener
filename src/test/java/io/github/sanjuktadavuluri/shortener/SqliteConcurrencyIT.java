package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Issue #50 (spec 0003, ADR 0021): SQLite runs in WAL mode with a 5000 ms busy timeout on every
 * pooled connection, and the pool holds 4 connections.
 */
class SqliteConcurrencyIT extends IntegrationTest {

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

  private static String pragma(Connection connection, String name) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA " + name)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }
}
