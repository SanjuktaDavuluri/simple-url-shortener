package io.github.sanjuktadavuluri.shortener.clicks;

/**
 * Takes Clicks off the Redirect path (ADR 0012). The Redirect hands each Click over and returns its
 * {@code 302} at once; how and when the Click is saved is the recorder's business, so a later move
 * to an event bus (ADR 0019 stage 4) replaces one implementation, not the Redirect.
 */
public interface ClickRecorder {

  /**
   * Hands over one Click. Returns immediately and never throws: a Click that can't be taken is
   * dropped and counted, so recording can never slow down or break a Redirect.
   */
  void record(Click click);
}
