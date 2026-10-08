package io.github.sanjuktadavuluri.shortener;

import java.util.Optional;

/** Where Links are kept. The only way the rest of the application touches storage (ADR 0002). */
public interface LinkStore {

  /**
   * Saves a Link pairing the Short Code with the Long URL, with the hash of its Manage Token (never
   * the token itself: ADR 0023).
   *
   * @throws ShortCodeTakenException if the Short Code already names a Link
   */
  void save(String shortCode, String longUrl, String manageTokenHash);

  /** Returns the Long URL of the Link with this Short Code, if there is one. */
  Optional<String> findLongUrl(String shortCode);
}
