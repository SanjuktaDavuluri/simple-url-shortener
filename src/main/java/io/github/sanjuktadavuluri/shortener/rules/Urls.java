package io.github.sanjuktadavuluri.shortener.rules;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/** Parsing shared by the Rules. */
final class Urls {

  private Urls() {}

  /** Parses the Long URL, or returns empty if it isn't a syntactically valid URI. */
  static Optional<URI> parse(String longUrl) {
    try {
      return Optional.of(new URI(longUrl));
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }
}
