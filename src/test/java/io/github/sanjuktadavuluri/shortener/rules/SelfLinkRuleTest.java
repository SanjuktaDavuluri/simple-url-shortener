package io.github.sanjuktadavuluri.shortener.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelfLinkRuleTest {

  private static final RuleResult SELF_LINK =
      RuleResult.rejected("Links to this shortener aren't allowed.");

  @Nested
  class WhenTheBaseUrlHasNoPort {

    private final Rule rule = new SelfLinkRule("http://sho.rt");

    @ParameterizedTest
    @ValueSource(
        strings = {
          "http://sho.rt/Ab3xK9q",
          "HTTP://SHO.RT/Ab3xK9q",
          "https://sho.rt/Ab3xK9q",
          "http://sho.rt:8080/Ab3xK9q"
        })
    void anyLongUrlOnTheSameHostIsASelfLink(String longUrl) {
      assertThat(rule.check(longUrl)).isEqualTo(SELF_LINK);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"https://example.com", "http://sho.rt.example.com/x", "http://notsho.rt/x"})
    void otherHostsPass(String longUrl) {
      assertThat(rule.check(longUrl)).isEqualTo(RuleResult.passed());
    }
  }

  @Nested
  class WhenTheBaseUrlHasAPort {

    private final Rule rule = new SelfLinkRule("http://localhost:8000");

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8000/Ab3xK9q", "http://LOCALHOST:8000/x"})
    void theSameHostAndPortIsASelfLink(String longUrl) {
      assertThat(rule.check(longUrl)).isEqualTo(SELF_LINK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000/x", "http://localhost/x"})
    void theSameHostOnAnotherPortPasses(String longUrl) {
      assertThat(rule.check(longUrl)).isEqualTo(RuleResult.passed());
    }
  }
}
