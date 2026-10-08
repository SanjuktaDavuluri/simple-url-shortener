package io.github.sanjuktadavuluri.shortener;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Keeps Links in SQLite with plain SQL (ADR 0002). The schema is owned by Flyway; the primary key
 * on {@code short_code} makes the database refuse duplicate Short Codes.
 */
@Repository
class JdbcLinkStore implements LinkStore {

  /** The format of {@code links.created_at}: ISO-8601 UTC to the millisecond. */
  private static final DateTimeFormatter EXPIRES_AT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private final JdbcClient jdbc;

  JdbcLinkStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(String shortCode, String longUrl, Optional<Instant> expiry) {
    try {
      jdbc.sql(
              "INSERT INTO links (short_code, long_url, expires_at)"
                  + " VALUES (:shortCode, :longUrl, :expiresAt)")
          .param("shortCode", shortCode)
          .param("longUrl", longUrl)
          .param("expiresAt", expiry.map(EXPIRES_AT::format).orElse(null))
          .update();
    } catch (DataAccessException e) {
      if (isDuplicateShortCode(e)) {
        throw new ShortCodeTakenException(shortCode, e);
      }
      throw e;
    }
  }

  /** The database, not the application, decides that a Short Code is taken (ADR 0003). */
  private static boolean isDuplicateShortCode(DataAccessException e) {
    return e instanceof DuplicateKeyException
        || (NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLiteException sqlite
            && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY);
  }

  @Override
  public Optional<Destination> findDestination(String shortCode) {
    return jdbc.sql("SELECT long_url, expires_at FROM links WHERE short_code = :shortCode")
        .param("shortCode", shortCode)
        .query(
            (row, rowNumber) ->
                new Destination(
                    row.getString("long_url"),
                    Optional.ofNullable(row.getString("expires_at")).map(Instant::parse)))
        .optional();
  }
}
