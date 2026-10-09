package io.github.sanjuktadavuluri.shortener.clicks;

import java.time.Instant;
import java.util.List;

/**
 * Where Clicks are kept. The only way the rest of the application touches stored Clicks, as the
 * Link Store is for Links (ADR 0002, spec 0003). R2's Stats read through it (spec 0005).
 */
public interface ClickStore {

  /** Saves a batch of Clicks in one transaction: all of them, or none. */
  void saveAll(List<Click> clicks);

  /** Returns the Clicks of the Link with this Short Code, oldest first. */
  List<Click> listClicks(String shortCode);

  /**
   * Returns the Click Summary of the Link with this Short Code, every number read from the same
   * snapshot; an empty summary if it has no stored Clicks (spec 0005). {@code windowStart} is the
   * start of the Stats' 30-day window, the first UTC day the per-day counts cover; Clicks before it
   * still count everywhere else. Bot Clicks count only in the Agent Category split (ADR 0013).
   */
  ClickSummary summarise(String shortCode, Instant windowStart);
}
