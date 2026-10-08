package io.github.sanjuktadavuluri.shortener;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A Lifetime: the whole number of days, 1 to 365, that the person shortening a URL may give a Link
 * when creating it (spec 0004). It is not a Rule (Rules check Long URLs only, ADR 0004), so an
 * invalid one gives a validation message, not a Rejection Reason. The range is a constant here, not
 * configuration.
 */
public record Lifetime(int days) {

  static final int MIN_DAYS = 1;
  static final int MAX_DAYS = 365;

  private static final Pattern WHOLE_DAYS = Pattern.compile("[0-9]{1,3}");

  /** The validation message for any value that isn't a Lifetime (spec 0004, API contract). */
  public static final String INVALID =
      "expires_in_days must be a whole number of days from 1 to 365.";

  public Lifetime {
    if (days < MIN_DAYS || days > MAX_DAYS) {
      throw new InvalidLifetimeException();
    }
  }

  /**
   * The Lifetime of this many days.
   *
   * @throws InvalidLifetimeException if it isn't from 1 to 365
   */
  public static Lifetime ofDays(int days) {
    return new Lifetime(days);
  }

  /**
   * The Lifetime typed as text, as the web page sends it: digits only, so decimals, signs,
   * exponents, spaces and other text are invalid, never coerced.
   *
   * @throws InvalidLifetimeException if the text isn't a whole number of days from 1 to 365
   */
  public static Lifetime parse(String text) {
    // At most three digits: anything longer is over 365 and could overflow an int.
    if (!WHOLE_DAYS.matcher(text).matches()) {
      throw new InvalidLifetimeException();
    }
    return ofDays(Integer.parseInt(text));
  }

  /**
   * The Expiry of a Link created at this instant: the creation instant plus the Lifetime in exact
   * 24-hour days, with no calendar or time-zone arithmetic.
   */
  public Instant expiryFrom(Instant created) {
    return created.plus(Duration.ofDays(days));
  }
}
