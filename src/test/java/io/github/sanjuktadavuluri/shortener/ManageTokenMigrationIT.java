package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Issue #104 (spec 0005, story 26): a database from before the Manage Token (R10: schema V2, with
 * Links and Clicks) migrates forward when the application starts on it. Its Links get no Manage
 * Token hash and keep Redirecting exactly as before, and their Clicks stay.
 */
class ManageTokenMigrationIT {

  private static final String BASE_URL = "http://sho.rt";

  @Test
  void anR10DatabaseMigratesForwardAndItsLinksKeepRedirectingWithoutAManageTokenHash() {
    Path database = TestDatabases.newFile();
    anR10DatabaseWithALinkAndAClick(database);

    try (ConfigurableApplicationContext app =
        TestApps.start(database, "--shortener.base-url=" + BASE_URL)) {
      assertThat(TestApps.mvc(app).get().uri("/Old1234"))
          .hasStatus(302)
          .hasHeader("Location", "https://example.com/from-r10")
          .hasHeader("Cache-Control", "no-store");

      JdbcClient jdbc = app.getBean(JdbcClient.class);
      assertThat(
              jdbc.sql("SELECT manage_token_hash IS NULL FROM links WHERE short_code = 'Old1234'")
                  .query(Boolean.class)
                  .single())
          .isTrue();
      assertThat(app.getBean(ClickStore.class).listClicks("Old1234")).isNotEmpty();
    }
  }

  /** Builds the schema exactly as R10 shipped it (V1 and V2) and stores one Link and one Click. */
  private static void anR10DatabaseWithALinkAndAClick(Path database) {
    SingleConnectionDataSource dataSource =
        new SingleConnectionDataSource("jdbc:sqlite:" + database, true);
    try {
      Flyway.configure().dataSource(dataSource).target("2").load().migrate();
      JdbcClient jdbc = JdbcClient.create(dataSource);
      jdbc.sql(
              "INSERT INTO links (short_code, long_url)"
                  + " VALUES ('Old1234', 'https://example.com/from-r10')")
          .update();
      jdbc.sql(
              "INSERT INTO clicks (short_code, clicked_at, referrer_host, agent_category,"
                  + " device_class) VALUES ('Old1234', '2026-10-01T09:15:02.123Z', NULL,"
                  + " 'browser', 'desktop')")
          .update();
    } finally {
      dataSource.destroy();
    }
  }
}
