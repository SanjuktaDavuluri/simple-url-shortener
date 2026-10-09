package io.github.sanjuktadavuluri.shortener;

import java.net.URI;
import java.net.URISyntaxException;
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

  /** Validates the settings (spec 0006) and removes trailing slashes from the Base URL. */
  public ShortenerProperties {
    baseUrl = validBaseUrl(baseUrl);
  }

  private static String validBaseUrl(String value) {
    URI uri = null;
    try {
      uri = value == null ? null : new URI(value);
    } catch (URISyntaxException e) {
      // reported below
    }
    boolean valid =
        uri != null
            && uri.isAbsolute()
            && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            && uri.getHost() != null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null;
    if (!valid) {
      throw new IllegalArgumentException(
          "BASE_URL must be an absolute http or https URL with a host and no query or fragment,"
              + " but was '"
              + value
              + "'");
    }
    return value.replaceAll("/+$", "");
  }

  /**
   * Click Recorder settings (spec 0003).
   *
   * @param queueCapacity how many Clicks may wait to be saved; more are dropped and counted
   * @param batchSize the most Clicks saved with one Click Store call
   * @param flushInterval the longest the writer waits for a batch to fill before saving it
   * @param shutdownTimeout the longest a normal shutdown waits for queued Clicks to be saved
   */
  public record Clicks(
      int queueCapacity, int batchSize, Duration flushInterval, Duration shutdownTimeout) {

    /** Validates the settings (spec 0006). */
    public Clicks {
      requirePositive("CLICK_QUEUE_CAPACITY", queueCapacity > 0, queueCapacity);
      requirePositive("CLICK_BATCH_SIZE", batchSize > 0, batchSize);
      requirePositive("CLICK_FLUSH_INTERVAL", positive(flushInterval), flushInterval);
      requirePositive("CLICK_SHUTDOWN_TIMEOUT", positive(shutdownTimeout), shutdownTimeout);
      if (batchSize > queueCapacity) {
        throw new IllegalArgumentException(
            "CLICK_BATCH_SIZE ("
                + batchSize
                + ") must not be larger than CLICK_QUEUE_CAPACITY ("
                + queueCapacity
                + ")");
      }
    }

    private static boolean positive(Duration value) {
      return value != null && value.isPositive();
    }

    private static void requirePositive(String variable, boolean ok, Object value) {
      if (!ok) {
        throw new IllegalArgumentException(variable + " must be positive, but was " + value);
      }
    }
  }
}
