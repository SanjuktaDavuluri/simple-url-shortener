package io.github.sanjuktadavuluri.shortener.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WellFormedRuleTest {

  private final Rule rule = new WellFormedRule();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://example.com",
        "http://example.com:8080/a/b?q=1&r=two#section",
        "https://example.com/caf%C3%A9",
        "http://127.0.0.1/x",
        "ftp://example.com/left-for-the-scheme-rule"
      })
  void wellFormedWebAddressesPass(String longUrl) {
    assertThat(rule.check(longUrl)).isEqualTo(RuleResult.passed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://example.com/with space",
        "https://example.com/search?q=\"quoted\"",
        "https://example.com/<tag>",
        "https://example.com/{id}",
        "https://example.com/a|b",
        "https://example.com/100%zz"
      })
  void malformedWebAddressesAreRejected(String longUrl) {
    assertThat(rule.check(longUrl))
        .isEqualTo(
            RuleResult.rejected(
                "That isn't a valid web address. Check it for spaces or characters like"
                    + " \" < > { } |."));
  }
}
