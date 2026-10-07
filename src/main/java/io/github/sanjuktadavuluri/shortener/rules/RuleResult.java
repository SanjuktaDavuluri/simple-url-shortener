package io.github.sanjuktadavuluri.shortener.rules;

/** The outcome of checking a Long URL: it passed, or it was rejected with a Rejection Reason. */
public sealed interface RuleResult {

  static RuleResult passed() {
    return Passed.INSTANCE;
  }

  static RuleResult rejected(String rejectionReason) {
    return new Rejected(rejectionReason);
  }

  /** The Long URL passed. */
  enum Passed implements RuleResult {
    INSTANCE
  }

  /** The Long URL was rejected; the reason is written for a human and shown as-is. */
  record Rejected(String rejectionReason) implements RuleResult {}
}
