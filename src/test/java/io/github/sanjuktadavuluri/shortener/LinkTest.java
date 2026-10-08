package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Issue #104 (spec 0005, story 21): a Link holds its Manage Token in memory but never prints it.
 */
class LinkTest {

  private static final String SCRIPTED = "first-scripted-manage-token-for-tests-00001";

  @Test
  void aLinkCarriesItsManageTokenButItsTextFormLeavesTheTokenOut() {
    Link link =
        new Link("Ab3xK9q", "http://sho.rt/Ab3xK9q", "https://example.com/very/long", SCRIPTED);

    assertThat(link.manageToken()).isEqualTo(SCRIPTED);
    assertThat(link.toString())
        .contains("Ab3xK9q", "http://sho.rt/Ab3xK9q", "https://example.com/very/long")
        .doesNotContain(SCRIPTED);
  }
}
