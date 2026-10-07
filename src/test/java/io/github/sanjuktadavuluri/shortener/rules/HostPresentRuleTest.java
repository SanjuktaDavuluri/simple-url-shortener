package io.github.sanjuktadavuluri.shortener.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostPresentRuleTest {

  private final Rule rule = new HostPresentRule();

  @ParameterizedTest
  @ValueSource(
      strings = {"https://example.com", "http://example.com:8080/path?q=1", "http://127.0.0.1"})
  void longUrlsWithAHostPass(String longUrl) {
    assertThat(rule.check(longUrl)).isEqualTo(RuleResult.passed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://", "https:///only/a/path", "http://:8080", "https:"})
  void longUrlsWithoutAHostAreRejected(String longUrl) {
    assertThat(rule.check(longUrl))
        .isEqualTo(RuleResult.rejected("The web address needs a host name, like example.com."));
  }
}
