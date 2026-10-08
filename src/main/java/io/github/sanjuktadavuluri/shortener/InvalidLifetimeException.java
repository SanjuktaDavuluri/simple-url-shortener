package io.github.sanjuktadavuluri.shortener;

/** Thrown when a value isn't a {@link Lifetime}; its message is the validation message. */
public class InvalidLifetimeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidLifetimeException() {
    super(Lifetime.INVALID);
  }
}
