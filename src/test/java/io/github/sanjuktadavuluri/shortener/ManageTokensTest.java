package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Issue #104 (ADR 0023): a Manage Token is stored as the SHA-256 of its UTF-8 bytes, in hex. Issue
 * #105 (spec 0005 seam 4): a presented token is matched against the stored hash in constant time,
 * and a Link that is unknown or has no hash is still checked, against the dummy hash, and never
 * matches.
 */
class ManageTokensTest {

  /** The SHA-256 of {@link #aManageToken()}, worked out with {@code shasum -a 256}. */
  private static final String TOKEN_HASH =
      "156e9fe8b88aeb405a4d49ca2eddc0ee104fb4ba838a433b8d01944f9033234a";

  /** A Manage Token in the generator's shape (43 base64url characters), made up for these tests. */
  private static String aManageToken() {
    return "q0Vx3k2c9yJm1bT8wR4uLpA7sD6fG5hN2jK8mZ1xC0v";
  }

  @Test
  void hashingAManageTokenGivesItsSha256AsLowerCaseHex() {
    // Worked out independently (`printf '%s' <token> | shasum -a 256`), not by the code under test.
    assertThat(ManageTokens.hash(aManageToken())).isEqualTo(TOKEN_HASH);
  }

  @Test
  void theEmptyStringHashesToTheWellKnownSha256OfNothing() {
    assertThat(ManageTokens.hash(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void theManageTokenWhoseHashIsStoredMatches() {
    assertThat(ManageTokens.matches(aManageToken(), Optional.of(TOKEN_HASH))).isTrue();
  }

  @Test
  void anyOtherTokenDoesNotMatch() {
    assertThat(ManageTokens.matches(aManageToken() + "x", Optional.of(TOKEN_HASH))).isFalse();
    assertThat(
            ManageTokens.matches(
                aManageToken().toUpperCase(java.util.Locale.ROOT), Optional.of(TOKEN_HASH)))
        .isFalse();
    assertThat(ManageTokens.matches("", Optional.of(TOKEN_HASH))).isFalse();
    assertThat(ManageTokens.matches(TOKEN_HASH, Optional.of(TOKEN_HASH))).isFalse();
  }

  @Test
  void theDummyHashHasTheShapeOfAStoredHashSoComparingAgainstItCostsTheSame() {
    assertThat(ManageTokens.DUMMY_HASH).matches("[0-9a-f]{64}");
  }

  @Test
  void withNoStoredHashEvenTheTokenWhoseHashIsTheDummyHashNeverMatches() {
    // The unknown-Link and no-hash path still hashes the presented token and compares it against
    // the dummy hash (ADR 0023). This value hashes to exactly that dummy hash, so the comparison
    // itself succeeds, yet the answer must still be "no match".
    assertThat(ManageTokens.hash(ManageTokens.class.getName())).isEqualTo(ManageTokens.DUMMY_HASH);

    assertThat(ManageTokens.matches(ManageTokens.class.getName(), Optional.empty())).isFalse();
    assertThat(ManageTokens.matches(aManageToken(), Optional.empty())).isFalse();
    assertThat(ManageTokens.matches("", Optional.empty())).isFalse();
  }
}
