package io.github.sanjuktadavuluri.shortener;

/** Thrown by a {@link LinkStore} when the Short Code already names a Link (a Collision). */
public class ShortCodeTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ShortCodeTakenException(String shortCode, Throwable cause) {
    super("Short Code already taken: " + shortCode, cause);
  }
}
