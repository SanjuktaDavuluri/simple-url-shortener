package io.github.sanjuktadavuluri.shortener.rules;

import java.net.URI;
import java.util.Locale;

/**
 * Rejects Self-links: Long URLs that point back at the shortener's own Base URL, which could build
 * Redirect loops. Hosts are compared case-insensitively. If the Base URL names a port, the port
 * must match too; if it doesn't, any port on that host counts as the shortener.
 */
public final class SelfLinkRule implements Rule {

  static final String REJECTION_REASON = "Links to this shortener aren't allowed.";

  private final String baseHost;
  private final int basePort;

  public SelfLinkRule(String baseUrl) {
    URI base = URI.create(baseUrl);
    this.baseHost = base.getHost().toLowerCase(Locale.ROOT);
    this.basePort = base.getPort();
  }

  @Override
  public RuleResult check(String longUrl) {
    boolean isSelfLink = Urls.parse(longUrl).map(this::pointsAtTheShortener).orElse(false);
    return isSelfLink ? RuleResult.rejected("self_link", REJECTION_REASON) : RuleResult.passed();
  }

  private boolean pointsAtTheShortener(URI longUrl) {
    String host = longUrl.getHost();
    if (host == null || !host.toLowerCase(Locale.ROOT).equals(baseHost)) {
      return false;
    }
    return basePort == -1 || effectivePort(longUrl) == basePort;
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
