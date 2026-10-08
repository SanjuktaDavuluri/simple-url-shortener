package io.github.sanjuktadavuluri.shortener;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shortener configuration.
 *
 * @param baseUrl the shortener's public address; every Short URL starts with it
 * @param databasePath where the SQLite database file lives
 * @param clicks how the Click Recorder queues and saves Clicks (ADR 0012)
 */
@ConfigurationProperties("shortener")
public record ShortenerProperties(String baseUrl, String databasePath, Clicks clicks) {

  /**
   * Click Recorder settings (spec 0003).
   *
   * @param queueCapacity how many Clicks may wait to be saved; more are dropped and counted
   * @param batchSize the most Clicks saved with one Click Store call
   * @param flushInterval the longest the writer waits for a batch to fill before saving it
   */
  public record Clicks(int queueCapacity, int batchSize, Duration flushInterval) {}
}
