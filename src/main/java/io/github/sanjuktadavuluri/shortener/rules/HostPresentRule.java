package io.github.sanjuktadavuluri.shortener.rules;

import java.net.URI;

/** A Long URL must name a host, so every Link has a real destination. */
public final class HostPresentRule implements Rule {

  static final String REJECTION_REASON = "The web address needs a host name, like example.com.";

  @Override
  public RuleResult check(String longUrl) {
    boolean hasHost = Urls.parse(longUrl).map(URI::getHost).isPresent();
    return hasHost ? RuleResult.passed() : RuleResult.rejected("host_present", REJECTION_REASON);
  }
}
