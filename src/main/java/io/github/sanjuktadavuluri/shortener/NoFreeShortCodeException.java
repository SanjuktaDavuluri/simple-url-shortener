package io.github.sanjuktadavuluri.shortener;

/** Thrown when every attempt to draw a free Short Code ended in a Collision. */
public class NoFreeShortCodeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NoFreeShortCodeException(int attempts) {
    super("No free Short Code after " + attempts + " attempts");
  }
}
