package io.github.sanjuktadavuluri.shortener.rules;

/**
 * A Long URL must be a syntactically valid URI, so the other Rules only ever see parseable web
 * addresses and a malformed one gets a Rejection Reason that names the real problem.
 */
public final class WellFormedRule implements Rule {

  static final String REJECTION_REASON =
      "That isn't a valid web address. Check it for spaces or characters like \" < > { } |.";

  @Override
  public RuleResult check(String longUrl) {
    return Urls.parse(longUrl).isPresent()
        ? RuleResult.passed()
        : RuleResult.rejected(REJECTION_REASON);
  }
}
