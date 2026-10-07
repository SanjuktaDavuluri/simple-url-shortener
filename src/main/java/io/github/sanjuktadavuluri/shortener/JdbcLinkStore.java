package io.github.sanjuktadavuluri.shortener;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
  public void save(String shortCode, String longUrl) {
    jdbc.sql("INSERT INTO links (short_code, long_url) VALUES (:shortCode, :longUrl)")
        .param("shortCode", shortCode)
        .param("longUrl", longUrl)
        .update();
  }

  @Override
  public Optional<String> findLongUrl(String shortCode) {
    return jdbc.sql("SELECT long_url FROM links WHERE short_code = :shortCode")
        .param("shortCode", shortCode)
        .query(String.class)
        .optional();
  }
}
