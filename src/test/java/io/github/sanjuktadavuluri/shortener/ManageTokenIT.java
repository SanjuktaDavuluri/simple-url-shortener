package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issue #104 (spec 0005, stories 1, 2, 5, 6, 7 and 21; ADRs 0014 and 0023): creating a Link over
 * the JSON API returns its Manage Token once, and only the token's SHA-256 hash is stored.
 */
@ExtendWith(OutputCaptureExtension.class)
class ManageTokenIT extends IntegrationTest {

  /** Scripted Manage Tokens: plainly fake, but shaped like real ones (43 base64url characters). */
  private static final String FIRST = "first-scripted-manage-token-for-tests-00001";

  private static final String SECOND = "second-scripted-manage-token-for-tests-0002";

  /** The SHA-256 of each, worked out with {@code shasum -a 256}, not by the code under test. */
  private static final String FIRST_HASH =
      "2f6da021345588db6a010080f5a0b8745ce5054716c32f712a923563d76a623d";

  private static final String SECOND_HASH =
      "018f15577a6280a676334871dd70a5e8986398e7fc2bb0d35e24c4affa2b780f";

  @Autowired private Flyway flyway;

  @Test
  void creatingALinkReturnsItsManageTokenNextToTheFieldsItAlreadyHad() {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);

    assertThat(postLink("https://example.com/very/long"))
        .hasStatus(201)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "short_code": "Ab3xK9q",
              "short_url": "http://sho.rt/Ab3xK9q",
              "long_url": "https://example.com/very/long",
              "manage_token": "first-scripted-manage-token-for-tests-00001"
            }
            """);
  }

  @Test
  void theResponseCarryingTheManageTokenIsNotCached() {
    shortCodes.willReturn("Ab3xK9q");

    assertThat(postLink("https://example.com/very/long"))
        .hasStatus(201)
        .hasHeader("Cache-Control", "no-store");
  }

  @Test
  void eachLinkGetsItsOwnManageTokenAndOnlyItsHashIsStored() {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");
    manageTokens.willReturn(FIRST, SECOND);

    assertThat(createLinkForItsManageToken("https://example.com/first")).isEqualTo(FIRST);
    assertThat(createLinkForItsManageToken("https://example.com/second")).isEqualTo(SECOND);

    assertThat(storedManageTokenHash("Ab3xK9q")).contains(FIRST_HASH);
    assertThat(storedManageTokenHash("Zz9yX8w")).contains(SECOND_HASH);
  }

  @Test
  void twoLinksCreatedInARowWithRealTokensGetDifferentTokensStoredOnlyAsSha256Hex() {
    shortCodes.willReturn("Ab3xK9q", "Zz9yX8w");

    String first = createLinkForItsManageToken("https://example.com/first");
    String second = createLinkForItsManageToken("https://example.com/second");

    assertThat(first).isNotEqualTo(second);
    assertThat(storedManageTokenHash("Ab3xK9q"))
        .hasValueSatisfying(hash -> assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(first));
    assertThat(storedManageTokenHash("Zz9yX8w"))
        .hasValueSatisfying(hash -> assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(second));
  }

  @Test
  void aCollisionKeepsTheOneManageTokenDrawnForTheLink() {
    shortCodes.willReturn("Ab3xK9q", "Ab3xK9q", "Zz9yX8w");
    manageTokens.willReturn(FIRST, SECOND);
    createLink("https://example.com/first");

    assertThat(createLinkForItsManageToken("https://example.com/second")).isEqualTo(SECOND);
    assertThat(storedManageTokenHash("Zz9yX8w")).contains(SECOND_HASH);
    assertThat(manageTokens.draws()).isEqualTo(2);
  }

  @Test
  void aRejectedLongUrlGetsNoManageTokenAndDrawsNone() {
    MvcTestResult rejected = postLink("ftp://example.com/file");

    assertThat(rejected).hasStatus(422).bodyJson().doesNotHavePath("$.manage_token");
    assertThat(manageTokens.draws()).isZero();
  }

  @Test
  void aMalformedRequestGetsNoManageTokenAndDrawsNone() {
    MvcTestResult malformed = postBody("{}", MediaType.APPLICATION_JSON);

    assertThat(malformed).hasStatus(422).bodyJson().doesNotHavePath("$.manage_token");
    assertThat(manageTokens.draws()).isZero();
  }

  @Test
  void noFreeShortCodeGetsNoManageToken() {
    shortCodes.willReturn("Ab3xK9q", "Ab3xK9q", "Ab3xK9q", "Ab3xK9q", "Ab3xK9q", "Ab3xK9q");
    manageTokens.willReturn(FIRST, SECOND);
    createLink("https://example.com/first");

    MvcTestResult unavailable = postLink("https://example.com/second");

    assertThat(unavailable).hasStatus(503).bodyJson().doesNotHavePath("$.manage_token");
    assertThat(unavailable).body().asString().doesNotContain(SECOND);
  }

  @Test
  void theManageTokenAppearsInNoLogOutputOfACreate(CapturedOutput output) {
    shortCodes.willReturn("Ab3xK9q");
    manageTokens.willReturn(FIRST);

    assertThat(createLinkForItsManageToken("https://example.com/logged")).isEqualTo(FIRST);

    assertThat(output.getAll()).doesNotContain(FIRST).doesNotContain(FIRST_HASH);
  }

  @Test
  void theManageTokenHashMigrationIsAppliedToTheEmptyDatabaseBeforeEveryTest() {
    assertThat(flyway.info().current().getScript()).isEqualTo("V3__add_manage_token_hash.sql");
  }
}
