package io.github.sanjuktadavuluri.shortener;

import java.time.Instant;
import java.util.Optional;

/** Where Links are kept. The only way the rest of the application touches storage (ADR 0002). */
public interface LinkStore {

  /**
   * Saves a Link pairing the Short Code with the Long URL, and its Expiry if it has one.
   *
   * @throws ShortCodeTakenException if the Short Code already names a Link, Expired or not
   */
  void save(String shortCode, String longUrl, Optional<Instant> expiry);

  /**
   * Returns the Long URL and Expiry of the Link with this Short Code, if there is one: the Redirect
   * lookup, a single query by primary key.
   */
  Optional<Destination> findDestination(String shortCode);

  /** What a Redirect needs to know about a Link: where it points and when it expires, if ever. */
  record Destination(String longUrl, Optional<Instant> expiry) {

    /** Whether the Link is an Expired Link at this instant: it has an Expiry and now ≥ Expiry. */
    public boolean isExpiredAt(Instant now) {
      return expiry.isPresent() && !now.isBefore(expiry.get());
    }
  }
}
