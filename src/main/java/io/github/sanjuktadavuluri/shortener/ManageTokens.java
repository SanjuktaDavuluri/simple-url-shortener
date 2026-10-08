package io.github.sanjuktadavuluri.shortener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one place a Manage Token is hashed (ADR 0023): SHA-256 of its UTF-8 bytes, as 64 lower-case
 * hex characters, with no salt and no key. Only this hash is stored; the token itself never is.
 */
public final class ManageTokens {

  private ManageTokens() {}

  /** Returns the hash stored for this Manage Token in {@code links.manage_token_hash}. */
  public static String hash(String manageToken) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(manageToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform is required to provide SHA-256.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
