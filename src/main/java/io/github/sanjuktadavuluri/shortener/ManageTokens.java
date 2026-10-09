package io.github.sanjuktadavuluri.shortener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The one place a Manage Token is hashed and checked (ADR 0023): SHA-256 of its UTF-8 bytes, as 64
 * lower-case hex characters, with no salt and no key. Only this hash is stored; the token itself
 * never is. A presented token is matched against the stored hash in constant time.
 */
public final class ManageTokens {

  /**
   * The hash every unknown or hash-less Link is compared against, so that the check costs the same
   * whether or not the Link exists or has a hash: the hash of this class's name, a fixed value that
   * is no Link's Manage Token (it contains dots, which a generated token never does). Comparing
   * against it never counts as a match.
   */
  static final String DUMMY_HASH = hash(ManageTokens.class.getName());

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

  /**
   * Whether the presented token is the Manage Token whose hash is stored. The presented token is
   * always hashed and compared with {@link MessageDigest#isEqual} (constant time), against the
   * dummy hash when there is no stored hash (the Link is unknown or older than Manage Tokens), and
   * then never matches. So the time taken doesn't reveal whether the Short Code exists.
   */
  public static boolean matches(String presentedToken, Optional<String> storedHash) {
    boolean equal =
        MessageDigest.isEqual(
            hash(presentedToken).getBytes(StandardCharsets.US_ASCII),
            storedHash.orElse(DUMMY_HASH).getBytes(StandardCharsets.US_ASCII));
    return equal && storedHash.isPresent();
  }
}
