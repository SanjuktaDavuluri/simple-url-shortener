package io.github.sanjuktadavuluri.shortener.rules;

import java.util.List;

/**
 * The Rules currently in force, applied in a fixed order. A Long URL is accepted only if it passes
 * every Rule; the first Rejection Reason wins and later Rules are not consulted.
 */
public final class RuleSet {

  private final List<Rule> rules;

  public RuleSet(List<Rule> rules) {
    this.rules = List.copyOf(rules);
  }

  /** The v1 Rule Set (spec 0001): scheme, host, length, then Self-link. */
  public static RuleSet v1(String baseUrl) {
    return new RuleSet(
        List.of(
            new HttpSchemeRule(),
            new HostPresentRule(),
            new MaxLengthRule(),
            new SelfLinkRule(baseUrl)));
  }

  /** Returns {@link RuleResult#passed()} or the first Rule's rejection. */
  public RuleResult check(String longUrl) {
    for (Rule rule : rules) {
      RuleResult result = rule.check(longUrl);
      if (result instanceof RuleResult.Rejected) {
        return result;
      }
    }
    return RuleResult.passed();
  }
}
