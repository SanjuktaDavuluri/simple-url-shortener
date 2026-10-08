package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Issue #104 (spec 0005, ADR 0023): Manage Tokens are 256 random bits, unpadded base64url. */
class RandomManageTokenGeneratorTest {

  private final ManageTokenGenerator generator = new RandomManageTokenGenerator();

  @Test
  void manageTokensAreFortyThreeBase64UrlCharactersWithoutPadding() {
    IntStream.range(0, 1_000)
        .mapToObj(i -> generator.next())
        .forEach(token -> assertThat(token).matches("[A-Za-z0-9_-]{43}"));
  }

  @Test
  void manageTokensNeverRepeatOverManyDraws() {
    Set<String> tokens = new HashSet<>();
    IntStream.range(0, 10_000).forEach(i -> tokens.add(generator.next()));

    assertThat(tokens).hasSize(10_000);
  }
}
