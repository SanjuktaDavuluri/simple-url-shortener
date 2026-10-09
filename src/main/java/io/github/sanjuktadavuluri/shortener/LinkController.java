package io.github.sanjuktadavuluri.shortener;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickClassifier;
import io.github.sanjuktadavuluri.shortener.clicks.ClickRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The JSON API: create a Link, and follow a Short URL.
 *
 * <p>The create response is the only place a Link's Manage Token is ever shown, so it is sent with
 * {@code Cache-Control: no-store} and the token is never logged (spec 0005, ADR 0014).
 */
@RestController
class LinkController {

  private static final Logger log = LoggerFactory.getLogger(LinkController.class);

  static final String MALFORMED_REQUEST =
      "The request body must be JSON like {\"url\": \"https://example.com\"}.";

  static final String NO_FREE_SHORT_CODE = "Couldn't find a free Short Code. Please try again.";

  /** The body of the {@code 410 Gone} an Expired Link answers with (spec 0004). */
  static final String EXPIRED = "This link has expired.";

  private static final byte[] EXPIRED_BODY = EXPIRED.getBytes(StandardCharsets.UTF_8);

  private final LinkService linkService;
  private final LinkStore links;
  private final ClickRecorder clickRecorder;
  private final Clock clock;
  private final MeterRegistry metrics;

  LinkController(
      LinkService linkService,
      LinkStore links,
      ClickRecorder clickRecorder,
      Clock clock,
      MeterRegistry metrics) {
    this.linkService = linkService;
    this.links = links;
    this.clickRecorder = clickRecorder;
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * Creates a Link. Both fields are read as JSON nodes so nothing is coerced, and the request is
   * checked in order (spec 0004): its shape first (a {@code url} that is missing or not a string is
   * a malformed request), then the Lifetime, then the Rule Set (in {@link LinkService}).
   */
  @PostMapping("/links")
  @Operation(
      summary = "Create a Link",
      description =
          "Shortens a Long URL, optionally with a Lifetime. The Manage Token in the 201 response is"
              + " returned only by this create response: it is sent later as `Authorization:"
              + " Bearer <manage_token>`, never in a URL, and it cannot be recovered if lost.")
  @ApiResponse(
      responseCode = "201",
      description = "The Link was created. This is the only time the Manage Token is shown.",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = CreatedLink.class)),
      headers = @Header(name = "Cache-Control", description = "Always `no-store`."))
  @ApiResponse(
      responseCode = "400",
      description = "Malformed request.",
      content = @Content(mediaType = "application/problem+json"))
  @ApiResponse(
      responseCode = "422",
      description = "The URL was rejected by the Rule Set, or the Lifetime is invalid.",
      content = @Content(mediaType = "application/problem+json"))
  @ApiResponse(
      responseCode = "503",
      description = "No free Short Code could be found; try again.",
      content = @Content(mediaType = "application/problem+json"))
  @ResponseStatus(HttpStatus.CREATED)
  CreatedLink createLink(@RequestBody CreateLinkRequest request, HttpServletResponse response) {
    if (request.url() == null || !request.url().isString()) {
      throw new MalformedRequestException();
    }
    Optional<Lifetime> lifetime = lifetimeOf(request.expiresInDays());
    Link link = linkService.create(request.url().stringValue(), lifetime);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    return new CreatedLink(
        link.shortCode(),
        link.shortUrl(),
        link.longUrl(),
        link.manageToken(),
        link.expiry().map(Instant::toString).orElse(null));
  }

  /**
   * The Lifetime {@code expires_in_days} gives: none when it is absent or {@code null}, otherwise
   * only a JSON integer from 1 to 365. Strings, decimals, booleans and objects are never coerced.
   *
   * @throws InvalidLifetimeException if it is present and isn't a Lifetime
   */
  private static Optional<Lifetime> lifetimeOf(JsonNode expiresInDays) {
    if (expiresInDays == null || expiresInDays.isNull()) {
      return Optional.empty();
    }
    if (!expiresInDays.isIntegralNumber() || !expiresInDays.canConvertToInt()) {
      throw new InvalidLifetimeException();
    }
    return Optional.of(Lifetime.ofDays(expiresInDays.intValue()));
  }

  /**
   * Follows a Short URL. Only 7-character base62 paths can be Short Codes (ADR 0003), so other
   * single-segment paths such as {@code /favicon.ico} fall through to static resources.
   *
   * <p>This is the single point where Clicks are produced (spec 0003, ADR 0005). On a successful
   * {@code GET}, the {@code 302} (with an empty body) is sent to the visitor first, and only then
   * is one Click handed to the Click Recorder, so the Redirect never waits for Click recording,
   * even if a recorder broke its promise to return at once. Handing over can't throw into the
   * Redirect either: a failure costs that Click, never the Redirect (stories 2 and 3). A {@code
   * 404}, a {@code HEAD} request or an Expired Link records nothing. The raw {@code Referer} and
   * {@code User-Agent} only reach the Click Classifier (ADR 0013).
   *
   * <p>An Expired Link (it has an Expiry and the injected clock's {@code now >= Expiry}) answers
   * {@code 410 Gone} with a short plain-text body, no body for {@code HEAD}, and never reaches the
   * Click Recorder (spec 0004). The lookup stays a single query by primary key.
   */
  @GetMapping("/{short_code:[A-Za-z0-9]{7}}")
  void followLink(
      @PathVariable("short_code") String shortCode,
      HttpMethod method,
      @RequestHeader(name = HttpHeaders.REFERER, required = false) String referer,
      @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
      HttpServletResponse response)
      throws IOException {
    Optional<LinkStore.Destination> destination = links.findDestination(shortCode);
    if (HttpMethod.GET.equals(method)) {
      String outcome = destination.isPresent() ? "found" : "not_found";
      metrics.counter("shortener.redirects", "outcome", outcome).increment();
    }
    if (destination.isEmpty()) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      return;
    }
    if (destination.get().isExpiredAt(clock.instant())) {
      answerExpired(method, response);
      return;
    }
    response.setStatus(HttpStatus.FOUND.value());
    response.setHeader(
        HttpHeaders.LOCATION, URI.create(destination.get().longUrl()).toASCIIString());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setContentLength(0);
    response.flushBuffer();
    if (HttpMethod.GET.equals(method)) {
      handOverClick(shortCode, referer, userAgent);
    }
  }

  /**
   * {@code 410 Gone} for an Expired Link: the same status and headers for {@code HEAD}, no body.
   */
  private static void answerExpired(HttpMethod method, HttpServletResponse response)
      throws IOException {
    response.setStatus(HttpStatus.GONE.value());
    response.setContentType(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setContentLength(EXPIRED_BODY.length);
    if (!HttpMethod.HEAD.equals(method)) {
      response.getOutputStream().write(EXPIRED_BODY);
    }
    response.flushBuffer();
  }

  /** Builds the Click and hands it to the Click Recorder; never throws. */
  private void handOverClick(String shortCode, String referer, String userAgent) {
    try {
      clickRecorder.record(
          Click.of(shortCode, clock.instant(), ClickClassifier.classify(referer, userAgent)));
    } catch (RuntimeException e) {
      // The failure's type only: never the Click's contents or the raw headers (ADR 0013).
      log.warn("Dropped a Click: handing it over failed ({})", e.getClass().getSimpleName());
    }
  }

  @ExceptionHandler
  ProblemDetail rejected(RejectedLongUrlException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.rejectionReason());
  }

  @ExceptionHandler
  ProblemDetail noFreeShortCode(NoFreeShortCodeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, NO_FREE_SHORT_CODE);
  }

  @ExceptionHandler
  ProblemDetail invalidLifetime(InvalidLifetimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, Lifetime.INVALID);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MalformedRequestException.class})
  ProblemDetail malformed() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, MALFORMED_REQUEST);
  }

  /**
   * The request body, read as JSON nodes so the controller decides what is malformed or invalid.
   * {@code expires_in_days} is optional: absent or {@code null} means the Link never expires.
   */
  record CreateLinkRequest(
      @Schema(
              implementation = String.class,
              description = "The Long URL to shorten.",
              example = "https://example.com/some/long/path",
              requiredMode = Schema.RequiredMode.REQUIRED)
          JsonNode url,
      @JsonProperty("expires_in_days")
          @Schema(
              implementation = Integer.class,
              nullable = true,
              description =
                  "Optional Lifetime in whole days, 1 to 365. Absent or null means the Link never"
                      + " expires.",
              minimum = "1",
              maximum = "365",
              example = "30")
          JsonNode expiresInDays) {}

  /** {@code expires_at} is always present: ISO-8601 UTC, or {@code null} if it never expires. */
  record CreatedLink(
      @JsonProperty("short_code") @Schema(example = "aB3dE5g") String shortCode,
      @JsonProperty("short_url") @Schema(example = "https://sho.rt.example/aB3dE5g")
          String shortUrl,
      @JsonProperty("long_url") @Schema(example = "https://example.com/some/long/path")
          String longUrl,
      @JsonProperty("manage_token")
          @Schema(
              description =
                  "Shown only in this response, never in a URL, and cannot be recovered if lost."
                      + " Send it as `Authorization: Bearer <manage_token>`.",
              example = "EXAMPLE-PLACEHOLDER-TOKEN")
          String manageToken,
      @JsonProperty("expires_at")
          @Schema(
              nullable = true,
              description = "ISO-8601 UTC expiry, or null if the Link never expires.",
              example = "2030-01-01T00:00:00Z")
          String expiresAt) {

    /** Leaves the Manage Token out, so it can't reach a log line (spec 0005, story 21). */
    @Override
    public String toString() {
      return "CreatedLink[shortCode=%s, shortUrl=%s, longUrl=%s, expiresAt=%s]"
          .formatted(shortCode, shortUrl, longUrl, expiresAt);
    }
  }

  private static final class MalformedRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
