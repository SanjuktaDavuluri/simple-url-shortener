package io.github.sanjuktadavuluri.shortener.rules;

import java.net.URI;

/** Only {@code http} and {@code https} Long URLs can be shortened (scheme is case-insensitive). */
public final class HttpSchemeRule implements Rule {

  static final String REJECTION_REASON =
      "Only http:// and https:// web addresses can be shortened.";

  @Override
  public RuleResult check(String longUrl) {
    boolean isWebAddress =
        Urls.parse(longUrl)
            .map(URI::getScheme)
            .map(scheme -> scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
            .orElse(false);
    return isWebAddress
        ? RuleResult.passed()
        : RuleResult.rejected("http_scheme", REJECTION_REASON);
  }
}
