package io.github.sanjuktadavuluri.shortener;

import java.util.Optional;

/** Where Links are kept. The only way the rest of the application touches storage (ADR 0002). */
public interface LinkStore {

  /** Saves a Link pairing the Short Code with the Long URL. */
  void save(String shortCode, String longUrl);

  /** Returns the Long URL of the Link with this Short Code, if there is one. */
  Optional<String> findLongUrl(String shortCode);
}
