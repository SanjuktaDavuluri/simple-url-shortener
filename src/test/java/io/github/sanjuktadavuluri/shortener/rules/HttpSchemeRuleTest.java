package io.github.sanjuktadavuluri.shortener.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpSchemeRuleTest {

  private final Rule rule = new HttpSchemeRule();

  @ParameterizedTest
  @ValueSource(strings = {"http://example.com", "https://example.com", "HTTPS://example.com"})
  void webAddressesPass(String longUrl) {
    assertThat(rule.check(longUrl)).isEqualTo(RuleResult.passed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://example.com",
        "javascript:alert(1)",
        "mailto:someone@example.com",
        "example.com",
        "not a url at all"
      })
  void anythingElseIsRejected(String longUrl) {
    assertThat(rule.check(longUrl))
        .isEqualTo(
            RuleResult.rejected("Only http:// and https:// web addresses can be shortened."));
  }
}
