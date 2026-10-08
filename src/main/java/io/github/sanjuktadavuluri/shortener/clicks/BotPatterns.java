package io.github.sanjuktadavuluri.shortener.clicks;

import java.util.List;
import java.util.Locale;

/**
 * The maintained list of crawler and link-preview patterns (spec 0003). A user agent containing any
 * of them, in any case, has the bot Agent Category. This is the one place to add a pattern.
 */
final class BotPatterns {

  /** Lower-case substrings, matched case-insensitively. */
  static final List<String> PATTERNS =
      List.of(
          "bot",
          "crawler",
          "spider",
          "slurp",
          "facebookexternalhit",
          "embedly",
          "whatsapp",
          "skypeuripreview",
          "bingpreview");

  private BotPatterns() {}

  /** Whether the user agent contains any pattern; a missing user agent never matches. */
  static boolean matches(String userAgent) {
    if (userAgent == null) {
      return false;
    }
    String lowerCase = userAgent.toLowerCase(Locale.ROOT);
    return PATTERNS.stream().anyMatch(lowerCase::contains);
  }
}
