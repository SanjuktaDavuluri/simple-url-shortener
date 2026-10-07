package io.github.sanjuktadavuluri.shortener;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Draws 7-character base62 Short Codes from a cryptographically secure source (ADR 0003). */
@Component
class RandomShortCodeGenerator implements ShortCodeGenerator {

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final int LENGTH = 7;

  private final SecureRandom random = new SecureRandom();

  @Override
  public String next() {
    StringBuilder code = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }
}
