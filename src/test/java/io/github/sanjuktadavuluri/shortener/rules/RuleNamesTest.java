package io.github.sanjuktadavuluri.shortener.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Issue #120 (spec 0006 story 15, ADR 0015): a rejection names the Rule that made it. */
class RuleNamesTest {

  private static String ruleOf(Rule rule, String longUrl) {
    return ((RuleResult.Rejected) rule.check(longUrl)).rule();
  }

  @Test
  void eachRulesRejectionCarriesItsStableName() {
    assertThat(ruleOf(new WellFormedRule(), "not a url")).isEqualTo("well_formed");
    assertThat(ruleOf(new HttpSchemeRule(), "ftp://host.test")).isEqualTo("http_scheme");
    assertThat(ruleOf(new HostPresentRule(), "http://")).isEqualTo("host_present");
    assertThat(ruleOf(new MaxLengthRule(), "http://host.test/" + "a".repeat(5000)))
        .isEqualTo("max_length");
    assertThat(ruleOf(new SelfLinkRule("http://sho.rt"), "http://sho.rt/x"))
        .isEqualTo("self_link");
  }
}
