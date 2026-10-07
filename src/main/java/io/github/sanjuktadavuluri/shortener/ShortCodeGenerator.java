package io.github.sanjuktadavuluri.shortener;

/** Draws new Short Codes. Takes no input: a Short Code never depends on the Long URL (ADR 0003). */
public interface ShortCodeGenerator {

  /** Returns a newly drawn Short Code. */
  String next();
}
