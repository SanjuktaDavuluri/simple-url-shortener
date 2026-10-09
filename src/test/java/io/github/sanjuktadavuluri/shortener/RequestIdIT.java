package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issue #117 (spec 0006 stories 21–23, Testing Decisions seam 1): every public response carries an
 * {@code X-Request-Id} header a client or a person reporting a problem can quote. A safe incoming
 * ID (1–64 characters from {@code [A-Za-z0-9._-]}) is used as is; anything else is replaced by a
 * generated one, so a caller can't inject fake log lines or fields.
 *
 * <p>The forced {@code 500} is in {@link RequestIdOverHttpIT}, on its own instance.
 */
class RequestIdIT extends IntegrationTest {

  static final String HEADER = "X-Request-Id";

  /** A generated Request ID: a random (version 4) UUID. */
  static final String GENERATED =
      "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

  @Test
  void aLinkCreationCarriesARequestId() {
    shortCodes.willReturn("Ab3xK9q");

    assertThat(postLink("https://example.com/very/long")).hasStatus(201).containsHeader(HEADER);
  }

  @Test
  void aRedirectCarriesARequestIdNextToItsUnchangedHeaders() {
    shortCodes.willReturn("Ab3xK9q");
    createLink("https://example.com/very/long");

    assertThat(mvc.get().uri("/Ab3xK9q"))
        .hasStatus(302)
        .hasHeader("Location", "https://example.com/very/long")
        .hasHeader("Cache-Control", "no-store")
        .containsHeader(HEADER);
  }

  @Test
  void aRejectionCarriesARequestId() {
    // A Rejection keeps the status it always had (spec 0006: existing 4xx responses unchanged).
    assertThat(postLink("ftp://example.com/file"))
        .hasStatus(422)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .containsHeader(HEADER);
  }

  @Test
  void anUnknownShortCodeCarriesARequestId() {
    assertThat(mvc.get().uri("/Zz9yX8w")).hasStatus(404).containsHeader(HEADER);
  }

  @Test
  void theWebPageCarriesARequestId() {
    assertThat(mvc.get().uri("/")).hasStatus(200).containsHeader(HEADER);
  }

  @Test
  void aStaticAssetCarriesARequestId() {
    assertThat(mvc.get().uri("/css/app.css")).hasStatus(200).containsHeader(HEADER);
  }

  @Test
  void aSafeIncomingRequestIdIsEchoedBackUnchanged() {
    assertThat(mvc.get().uri("/").header(HEADER, "abc-123_DEF.4"))
        .hasHeader(HEADER, "abc-123_DEF.4");
  }

  @Test
  void aSixtyFourCharacterSafeRequestIdIsStillUsedAsIs() {
    String longest = "a".repeat(64);

    assertThat(mvc.get().uri("/").header(HEADER, longest)).hasHeader(HEADER, longest);
  }

  static Stream<Arguments> unsafeRequestIds() {
    return Stream.of(
        Arguments.of("empty", ""),
        Arguments.of("65 characters", "a".repeat(65)),
        Arguments.of("a newline", "abc\ninjected"),
        Arguments.of("a double quote", "abc\"def"),
        Arguments.of("a brace", "abc{def"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unsafeRequestIds")
  void anUnsafeIncomingRequestIdIsReplacedByAGeneratedOne(String why, String unsafe) {
    MvcTestResult result = mvc.get().uri("/").header(HEADER, unsafe).exchange();

    String requestId = result.getResponse().getHeader(HEADER);
    assertThat(requestId).isNotEqualTo(unsafe).matches(GENERATED);
  }

  @Test
  void twoRequestsWithoutARequestIdGetDifferentGeneratedOnes() {
    String first = mvc.get().uri("/").exchange().getResponse().getHeader(HEADER);
    String second = mvc.get().uri("/").exchange().getResponse().getHeader(HEADER);

    assertThat(first).matches(GENERATED);
    assertThat(second).matches(GENERATED).isNotEqualTo(first);
  }
}
