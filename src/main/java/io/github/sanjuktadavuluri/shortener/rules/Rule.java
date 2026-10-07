package io.github.sanjuktadavuluri.shortener.rules;

/** A single, independent check on a Long URL. */
public interface Rule {

  /** Returns whether the Long URL passes, or the Rejection Reason if it doesn't. */
  RuleResult check(String longUrl);
}
