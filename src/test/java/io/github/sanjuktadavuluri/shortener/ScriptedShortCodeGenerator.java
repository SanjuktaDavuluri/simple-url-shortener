package io.github.sanjuktadavuluri.shortener;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** A {@link ShortCodeGenerator} that returns the Short Codes it was given, in order. */
final class ScriptedShortCodeGenerator implements ShortCodeGenerator {

  private final Deque<String> codes = new ArrayDeque<>();

  /** Replaces the script with the given Short Codes. */
  void willReturn(String... shortCodes) {
    codes.clear();
    codes.addAll(Arrays.asList(shortCodes));
  }

  @Override
  public String next() {
    if (codes.isEmpty()) {
      throw new IllegalStateException("The test did not script enough Short Codes");
    }
    return codes.removeFirst();
  }
}
