package io.github.sanjuktadavuluri.shortener;

import java.time.Instant;
import java.util.Optional;

/** Where Links are kept. The only way the rest of the application touches storage (ADR 0002). */
public interface LinkStore {

  /**
   * Saves a Link pairing the Short Code with the Long URL, with the hash of its Manage Token (never
   * the token itself: ADR 0023), and its Expiry if it has one.
   *
   * @throws ShortCodeTakenException if the Short Code already names a Link, Expired or not
   */
  void save(String shortCode, String longUrl, String manageTokenHash, Optional<Instant> expiry);

  /**
   * Returns the Long URL and Expiry of the Link with this Short Code, if there is one: the Redirect
   * lookup, a single query by primary key.
   */
  Optional<Destination> findDestination(String shortCode);

  /**
   * Returns the Link with this Short Code, Expired or not, as the Stats need it: its Short Code,
   * Long URL, creation time and Manage Token hash (absent for a Link created before Manage Tokens).
   * A single query by primary key; the Redirect lookup is unchanged (spec 0005).
   */
  Optional<StoredLink> findForStats(String shortCode);

  /**
   * A stored Link as the Stats read it. Its {@link #toString()} leaves the Manage Token hash out,
   * so the hash can't reach a log line (spec 0005, story 21).
   */
  record StoredLink(
      String shortCode, String longUrl, Instant createdAt, Optional<String> manageTokenHash) {

    @Override
    public String toString() {
      return "StoredLink[shortCode=%s, longUrl=%s, createdAt=%s]"
          .formatted(shortCode, longUrl, createdAt);
    }
  }

  /** What a Redirect needs to know about a Link: where it points and when it expires, if ever. */
  record Destination(String longUrl, Optional<Instant> expiry) {

    /** Whether the Link is an Expired Link at this instant: it has an Expiry and now ≥ Expiry. */
    public boolean isExpiredAt(Instant now) {
      return expiry.isPresent() && !now.isBefore(expiry.get());
    }
  }
}
