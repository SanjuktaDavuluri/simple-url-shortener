package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Issue #104 (ADR 0023): a Manage Token is stored as the SHA-256 of its UTF-8 bytes, in hex. */
class ManageTokensTest {

  @Test
  void hashingAManageTokenGivesItsSha256AsLowerCaseHex() {
    // Worked out independently (`printf '%s' <token> | shasum -a 256`), not by the code under test.
    assertThat(ManageTokens.hash("q0Vx3k2c9yJm1bT8wR4uLpA7sD6fG5hN2jK8mZ1xC0v"))
        .isEqualTo("156e9fe8b88aeb405a4d49ca2eddc0ee104fb4ba838a433b8d01944f9033234a");
  }

  @Test
  void theEmptyStringHashesToTheWellKnownSha256OfNothing() {
    assertThat(ManageTokens.hash(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }
}
