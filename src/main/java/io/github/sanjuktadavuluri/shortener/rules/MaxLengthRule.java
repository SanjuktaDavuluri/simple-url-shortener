package io.github.sanjuktadavuluri.shortener.rules;

/** Long URLs are capped at 2048 characters, keeping storage and Redirect headers sane. */
public final class MaxLengthRule implements Rule {

  static final int MAX_LENGTH = 2048;
  static final String REJECTION_REASON =
      "Web addresses longer than " + MAX_LENGTH + " characters can't be shortened.";

  @Override
  public RuleResult check(String longUrl) {
    return longUrl.length() <= MAX_LENGTH
        ? RuleResult.passed()
        : RuleResult.rejected(REJECTION_REASON);
  }
}
