package io.github.sanjuktadavuluri.shortener;

import java.time.Instant;
import java.util.Optional;

/**
 * A newly created Link: its Short Code, its Short URL, the Long URL it points to, its Manage Token,
 * and its Expiry (none for a Link created without a Lifetime, which never expires).
 *
 * <p>The Manage Token is held in plain text only here, in memory, between creation and the response
 * that returns it once; only its hash is stored (spec 0005, ADR 0023). It is never logged, so
 * {@link #toString()} leaves it out.
 */
public record Link(
    String shortCode,
    String shortUrl,
    String longUrl,
    String manageToken,
    Optional<Instant> expiry) {

  @Override
  public String toString() {
    return "Link[shortCode=%s, shortUrl=%s, longUrl=%s, expiry=%s]"
        .formatted(shortCode, shortUrl, longUrl, expiry);
  }
}
