package io.github.sanjuktadavuluri.shortener;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Draws Manage Tokens of 32 bytes (256 bits) from a cryptographically secure source, encoded as
 * unpadded base64url: 43 characters. Storing them as a plain SHA-256 hash is safe only because they
 * are this random (ADR 0023), so don't shorten them or weaken the source without a new ADR.
 */
@Component
class RandomManageTokenGenerator implements ManageTokenGenerator {

  private static final int BYTES = 32;

  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private final SecureRandom random = new SecureRandom();

  @Override
  public String next() {
    byte[] bytes = new byte[BYTES];
    random.nextBytes(bytes);
    return BASE64URL.encodeToString(bytes);
  }
}
