package io.github.sanjuktadavuluri.shortener;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * A {@link ManageTokenGenerator} that returns the Manage Tokens it was given, in order. Once the
 * script runs out it draws real random tokens, so tests that don't care about the token (most of
 * them) needn't script one. It counts every draw, so a test can prove a path drew none.
 */
final class ScriptedManageTokenGenerator implements ManageTokenGenerator {

  private final Deque<String> tokens = new ArrayDeque<>();

  private final ManageTokenGenerator unscripted = new RandomManageTokenGenerator();

  private int draws;

  /** Replaces the script with the given Manage Tokens and resets the draw count. */
  synchronized void willReturn(String... manageTokens) {
    tokens.clear();
    tokens.addAll(Arrays.asList(manageTokens));
    draws = 0;
  }

  /** How many Manage Tokens were drawn since the script was last set. */
  synchronized int draws() {
    return draws;
  }

  @Override
  public synchronized String next() {
    draws++;
    return tokens.isEmpty() ? unscripted.next() : tokens.removeFirst();
  }
}
