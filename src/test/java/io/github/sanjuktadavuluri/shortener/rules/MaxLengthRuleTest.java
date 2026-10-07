package io.github.sanjuktadavuluri.shortener.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaxLengthRuleTest {

  private static final String PREFIX = "https://example.com/";

  private final Rule rule = new MaxLengthRule();

  @Test
  void aLongUrlOfExactly2048CharactersPasses() {
    String longUrl = PREFIX + "a".repeat(2048 - PREFIX.length());

    assertThat(longUrl).hasSize(2048);
    assertThat(rule.check(longUrl)).isEqualTo(RuleResult.passed());
  }

  @Test
  void aLongUrlOf2049CharactersIsRejected() {
    String longUrl = PREFIX + "a".repeat(2049 - PREFIX.length());

    assertThat(longUrl).hasSize(2049);
    assertThat(rule.check(longUrl))
        .isEqualTo(
            RuleResult.rejected("Web addresses longer than 2048 characters can't be shortened."));
  }
}
