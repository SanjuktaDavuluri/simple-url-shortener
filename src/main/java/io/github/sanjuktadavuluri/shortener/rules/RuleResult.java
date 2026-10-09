package io.github.sanjuktadavuluri.shortener.rules;

/** The outcome of checking a Long URL: it passed, or it was rejected with a Rejection Reason. */
public sealed interface RuleResult {

  static RuleResult passed() {
    return Passed.INSTANCE;
  }

  /** A rejection by no particular Rule: the rule name is {@link Rejected#UNNAMED}. */
  static RuleResult rejected(String rejectionReason) {
    return new Rejected(Rejected.UNNAMED, rejectionReason);
  }

  /** A rejection by the Rule with this stable name (e.g. {@code self_link}). */
  static RuleResult rejected(String rule, String rejectionReason) {
    return new Rejected(rule, rejectionReason);
  }

  /** The Long URL passed. */
  enum Passed implements RuleResult {
    INSTANCE
  }

  /**
   * The Long URL was rejected; the reason is written for a human and shown as-is. The rule is the
   * stable, metric-safe name of the Rule that rejected it. Two rejections are equal when their
   * reasons are: the name only labels metrics (ADR 0015), it is not part of the outcome.
   */
  record Rejected(String rule, String rejectionReason) implements RuleResult {

    static final String UNNAMED = "unnamed";

    @Override
    public boolean equals(Object other) {
      return other instanceof Rejected that && rejectionReason.equals(that.rejectionReason);
    }

    @Override
    public int hashCode() {
      return rejectionReason.hashCode();
    }
  }
}
