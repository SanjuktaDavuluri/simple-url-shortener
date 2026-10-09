package io.github.sanjuktadavuluri.shortener.clicks;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps Clicks in SQLite with plain SQL (ADR 0002, spec 0003). It is the only code that touches the
 * {@code clicks} table; the schema is owned by Flyway.
 */
@Repository
class JdbcClickStore implements ClickStore {

  /** The format of {@code links.created_at}: ISO-8601 UTC to the millisecond. */
  private static final DateTimeFormatter CLICKED_AT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  /** Rows per multi-row INSERT, far below SQLite's limit on bound parameters. */
  private static final int ROWS_PER_STATEMENT = 100;

  private static final String INSERT =
      "INSERT INTO clicks (short_code, clicked_at, referrer_host, agent_category, device_class)"
          + " VALUES ";

  private static final String ROW = "(?, ?, ?, ?, ?)";

  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;

  JdbcClickStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @Override
  public void saveAll(List<Click> clicks) {
    if (clicks.isEmpty()) {
      return;
    }
    transaction.executeWithoutResult(
        status -> {
          for (int from = 0; from < clicks.size(); from += ROWS_PER_STATEMENT) {
            insert(clicks.subList(from, Math.min(from + ROWS_PER_STATEMENT, clicks.size())));
          }
        });
  }

  /** Inserts the rows with one statement, a batch insert in plain SQL. */
  private void insert(List<Click> rows) {
    List<Object> params = new ArrayList<>(rows.size() * 5);
    for (Click click : rows) {
      params.add(click.shortCode());
      params.add(CLICKED_AT.format(click.clickedAt()));
      params.add(click.referrerHost().orElse(null));
      params.add(click.agentCategory().name().toLowerCase(Locale.ROOT));
      params.add(click.deviceClass().name().toLowerCase(Locale.ROOT));
    }
    jdbc.sql(INSERT + String.join(", ", Collections.nCopies(rows.size(), ROW)))
        .params(params)
        .update();
  }

  @Override
  public List<Click> listClicks(String shortCode) {
    return jdbc.sql(
            """
            SELECT short_code, clicked_at, referrer_host, agent_category, device_class
            FROM clicks
            WHERE short_code = :shortCode
            ORDER BY clicked_at, id
            """)
        .param("shortCode", shortCode)
        .query((row, rowNumber) -> click(row))
        .list();
  }

  /**
   * Aggregates in SQL with {@code GROUP BY} over the {@code (short_code, clicked_at)} index, inside
   * one transaction, so every number comes from the same snapshot (WAL, ADR 0021) and the Clicks
   * are never all loaded into memory. Bots are left out of the last Click (ADR 0013).
   */
  @Override
  public ClickSummary summarise(String shortCode, Instant windowStart) {
    return transaction.execute(
        status -> {
          Map<AgentCategory, Long> byAgentCategory = new EnumMap<>(AgentCategory.class);
          jdbc.sql(
                  """
                  SELECT agent_category, COUNT(*) AS clicks
                  FROM clicks
                  WHERE short_code = :shortCode
                  GROUP BY agent_category
                  """)
              .param("shortCode", shortCode)
              .query(
                  row -> {
                    byAgentCategory.put(
                        AgentCategory.valueOf(
                            row.getString("agent_category").toUpperCase(Locale.ROOT)),
                        row.getLong("clicks"));
                  });
          Optional<Instant> lastClickAt =
              jdbc.sql(
                      """
                      SELECT MAX(clicked_at)
                      FROM clicks
                      WHERE short_code = :shortCode AND agent_category <> 'bot'
                      """)
                  .param("shortCode", shortCode)
                  .query((row, rowNumber) -> Optional.ofNullable(row.getString(1)))
                  .single()
                  .map(Instant::parse);
          return new ClickSummary(byAgentCategory, lastClickAt);
        });
  }

  private static Click click(ResultSet row) throws SQLException {
    return new Click(
        row.getString("short_code"),
        Instant.parse(row.getString("clicked_at")),
        Optional.ofNullable(row.getString("referrer_host")),
        AgentCategory.valueOf(row.getString("agent_category").toUpperCase(Locale.ROOT)),
        DeviceClass.valueOf(row.getString("device_class").toUpperCase(Locale.ROOT)));
  }
}
