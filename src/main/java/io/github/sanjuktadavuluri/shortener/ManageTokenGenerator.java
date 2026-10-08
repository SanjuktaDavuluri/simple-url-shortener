package io.github.sanjuktadavuluri.shortener;

/**
 * Draws new Manage Tokens: the secret a Link's creator receives once, which authorises reading its
 * Stats (spec 0005, ADR 0014). Behind an interface so tests can script it.
 */
public interface ManageTokenGenerator {

  /** Returns a newly drawn Manage Token. */
  String next();
}
