package io.github.sanjuktadavuluri.shortener;

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

  private final JdbcClient jdbc;

  JdbcLinkStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(String shortCode, String longUrl, String manageTokenHash) {
    try {
      jdbc.sql(
              "INSERT INTO links (short_code, long_url, manage_token_hash)"
                  + " VALUES (:shortCode, :longUrl, :manageTokenHash)")
          .param("shortCode", shortCode)
          .param("longUrl", longUrl)
          .param("manageTokenHash", manageTokenHash)
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
  public Optional<String> findLongUrl(String shortCode) {
    return jdbc.sql("SELECT long_url FROM links WHERE short_code = :shortCode")
        .param("shortCode", shortCode)
        .query(String.class)
        .optional();
  }
}
