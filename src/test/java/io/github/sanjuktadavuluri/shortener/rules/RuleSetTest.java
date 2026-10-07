package io.github.sanjuktadavuluri.shortener.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleSetTest {

  private final RuleSet v1 = RuleSet.v1("http://sho.rt");

  @Test
  void aWebAddressThatBreaksNoRulePasses() {
    assertThat(v1.check("https://example.com/very/long")).isEqualTo(RuleResult.passed());
  }

  @Test
  void aMalformedWebAddressIsRejectedForBeingMalformedNotForItsScheme() {
    assertThat(v1.check("https://example.com/with space"))
        .isEqualTo(
            RuleResult.rejected(
                "That isn't a valid web address. Check it for spaces or characters like"
                    + " \" < > { } |."));
  }

  @Test
  void theSchemeIsCheckedBeforeTheLength() {
    String longUrl = "ftp://example.com/" + "a".repeat(3000);

    assertThat(v1.check(longUrl))
        .isEqualTo(
            RuleResult.rejected("Only http:// and https:// web addresses can be shortened."));
  }

  @Test
  void theHostIsCheckedBeforeTheLength() {
    String longUrl = "https:///" + "a".repeat(3000);

    assertThat(v1.check(longUrl))
        .isEqualTo(RuleResult.rejected("The web address needs a host name, like example.com."));
  }

  @Test
  void theLengthIsCheckedBeforeSelfLinks() {
    String longUrl = "http://sho.rt/" + "a".repeat(3000);

    assertThat(v1.check(longUrl))
        .isEqualTo(
            RuleResult.rejected("Web addresses longer than 2048 characters can't be shortened."));
  }

  @Test
  void aSelfLinkIsRejected() {
    assertThat(v1.check("http://sho.rt/Ab3xK9q"))
        .isEqualTo(RuleResult.rejected("Links to this shortener aren't allowed."));
  }

  @Test
  void rulesAfterTheFirstRejectionAreNotConsulted() {
    List<String> consulted = new ArrayList<>();
    RuleSet ruleSet =
        new RuleSet(
            List.of(
                url -> {
                  consulted.add("first");
                  return RuleResult.rejected("first says no");
                },
                url -> {
                  consulted.add("second");
                  return RuleResult.rejected("second says no");
                }));

    assertThat(ruleSet.check("https://example.com"))
        .isEqualTo(RuleResult.rejected("first says no"));
    assertThat(consulted).containsExactly("first");
  }
}
