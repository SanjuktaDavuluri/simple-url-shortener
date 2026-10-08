package io.github.sanjuktadavuluri.shortener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A fixed UTC {@link Clock} that tests set and move forward by hand, so the time of each Click is
 * known in advance.
 */
final class TestClock extends Clock {

  private volatile Instant now;

  TestClock(Instant now) {
    this.now = now;
  }

  /** Fixes the clock at this instant. */
  void set(Instant instant) {
    now = instant;
  }

  /** Moves the clock forward. */
  void advance(Duration duration) {
    now = now.plus(duration);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return Clock.fixed(now, zone);
  }
}
