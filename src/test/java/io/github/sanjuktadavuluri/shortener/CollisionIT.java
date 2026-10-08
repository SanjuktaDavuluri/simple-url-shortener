package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Issue #4: a Collision is retried; when every attempt collides the client gets a clear 503. Issue
 * #99 (spec 0004 stories 17 and 20, ADR 0022): a Short Code that names an Expired Link is a
 * Collision too, so no Short Code is ever reused.
 */
class CollisionIT extends IntegrationTest {

  private static final String TAKEN = "Ab3xK9q";

  @BeforeEach
  void aLinkAlreadyUsesTheShortCode() {
    shortCodes.willReturn(TAKEN);
    createLink("https://example.com/first");
  }

  @Test
  void aCollisionIsRetriedWithTheNextShortCode() {
    shortCodes.willReturn(TAKEN, "Zz9yX8w");

    assertThat(postLink("https://example.com/second"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.short_code")
        .isEqualTo("Zz9yX8w");
    assertThat(mvc.get().uri("/" + TAKEN)).hasHeader("Location", "https://example.com/first");
    assertThat(mvc.get().uri("/Zz9yX8w")).hasHeader("Location", "https://example.com/second");
  }

  @Test
  void theFifthAttemptCanStillSucceed() {
    shortCodes.willReturn(TAKEN, TAKEN, TAKEN, TAKEN, "Zz9yX8w");

    assertThat(postLink("https://example.com/second"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.short_code")
        .isEqualTo("Zz9yX8w");
  }

  @Test
  void fiveCollisionsInARowFailWithAClearRetryableError() {
    shortCodes.willReturn(TAKEN, TAKEN, TAKEN, TAKEN, TAKEN, "Unused1");

    assertThat(postLink("https://example.com/second"))
        .hasStatus(503)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Couldn't find a free Short Code. Please try again.");

    // Exactly five attempts: the sixth scripted Short Code was never drawn.
    assertThat(postLink("https://example.com/third"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.short_code")
        .isEqualTo("Unused1");
  }

  @Test
  void aShortCodeThatNamesAnExpiredLinkIsACollisionAndTheExpiredLinkStaysGone() {
    shortCodes.willReturn("Exp1234");
    assertThat(postLink("https://example.com/event", 1)).hasStatus(201);
    clock.advance(Duration.ofDays(2));
    assertThat(mvc.get().uri("/Exp1234")).hasStatus(410);

    shortCodes.willReturn("Exp1234", "Zz9yX8w");

    assertThat(postLink("https://example.com/second"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.short_code")
        .isEqualTo("Zz9yX8w");
    assertThat(mvc.get().uri("/Exp1234"))
        .hasStatus(410)
        .doesNotContainHeader("Location")
        .hasBodyTextEqualTo("This link has expired.");
    assertThat(mvc.get().uri("/Zz9yX8w")).hasHeader("Location", "https://example.com/second");
  }
}
