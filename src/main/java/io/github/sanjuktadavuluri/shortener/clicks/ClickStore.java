package io.github.sanjuktadavuluri.shortener.clicks;

import java.util.List;

/**
 * Where Clicks are kept. The only way the rest of the application touches stored Clicks, as the
 * Link Store is for Links (ADR 0002, spec 0003). R2's stats API reads through it.
 */
public interface ClickStore {

  /** Saves a batch of Clicks in one transaction: all of them, or none. */
  void saveAll(List<Click> clicks);

  /** Returns the Clicks of the Link with this Short Code, oldest first. */
  List<Click> listClicks(String shortCode);
}
