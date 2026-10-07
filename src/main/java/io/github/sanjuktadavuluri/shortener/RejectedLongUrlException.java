package io.github.sanjuktadavuluri.shortener;

/** Thrown when a Long URL breaks a Rule; carries the Rule's Rejection Reason. */
public class RejectedLongUrlException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RejectedLongUrlException(String rejectionReason) {
    super(rejectionReason);
  }

  public String rejectionReason() {
    return getMessage();
  }
}
