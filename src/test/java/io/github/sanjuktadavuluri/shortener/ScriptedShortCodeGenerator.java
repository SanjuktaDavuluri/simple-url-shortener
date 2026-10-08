package io.github.sanjuktadavuluri.shortener;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A {@link ShortCodeGenerator} that returns the Short Codes it was given, in order. */
final class ScriptedShortCodeGenerator implements ShortCodeGenerator {

  private final Deque<String> codes = new ArrayDeque<>();

  /** The Short Codes drawn so far and not yet awaited, for tests that coordinate threads. */
  private final BlockingQueue<String> drawn = new LinkedBlockingQueue<>();

  /** Replaces the script with the given Short Codes. */
  void willReturn(String... shortCodes) {
    codes.clear();
    codes.addAll(Arrays.asList(shortCodes));
    drawn.clear();
  }

  /**
   * Waits up to the timeout for the next Short Code to be drawn, and returns it ({@code null} if
   * none was). Link creation draws a Short Code just before it saves the Link, so this tells a test
   * that a creation running on another thread has reached the Link Store.
   */
  String awaitDraw(Duration timeout) throws InterruptedException {
    return drawn.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public String next() {
    if (codes.isEmpty()) {
      throw new IllegalStateException("The test did not script enough Short Codes");
    }
    String shortCode = codes.removeFirst();
    drawn.add(shortCode);
    return shortCode;
  }
}
