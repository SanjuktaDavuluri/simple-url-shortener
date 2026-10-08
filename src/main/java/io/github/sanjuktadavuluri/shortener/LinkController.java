package io.github.sanjuktadavuluri.shortener;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickClassifier;
import io.github.sanjuktadavuluri.shortener.clicks.ClickRecorder;
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

/** The JSON API: create a Link, and follow a Short URL. */
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

  LinkController(
      LinkService linkService, LinkStore links, ClickRecorder clickRecorder, Clock clock) {
    this.linkService = linkService;
    this.links = links;
    this.clickRecorder = clickRecorder;
    this.clock = clock;
  }

  @PostMapping("/links")
  @ResponseStatus(HttpStatus.CREATED)
  CreatedLink createLink(@RequestBody CreateLinkRequest request) {
    if (request.url() == null) {
      throw new MalformedRequestException();
    }
    Optional<Lifetime> lifetime =
        Optional.ofNullable(request.expiresInDays()).map(Lifetime::ofDays);
    Link link = linkService.create(request.url(), lifetime);
    return new CreatedLink(
        link.shortCode(),
        link.shortUrl(),
        link.longUrl(),
        link.expiry().map(Instant::toString).orElse(null));
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
  @GetMapping("/{shortCode:[A-Za-z0-9]{7}}")
  void followLink(
      @PathVariable String shortCode,
      HttpMethod method,
      @RequestHeader(name = HttpHeaders.REFERER, required = false) String referer,
      @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
      HttpServletResponse response)
      throws IOException {
    Optional<LinkStore.Destination> destination = links.findDestination(shortCode);
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

  /** {@code expires_in_days} is optional: absent or {@code null} means the Link never expires. */
  record CreateLinkRequest(String url, @JsonProperty("expires_in_days") Integer expiresInDays) {}

  /** {@code expires_at} is always present: ISO-8601 UTC, or {@code null} if it never expires. */
  record CreatedLink(
      @JsonProperty("short_code") String shortCode,
      @JsonProperty("short_url") String shortUrl,
      @JsonProperty("long_url") String longUrl,
      @JsonProperty("expires_at") String expiresAt) {}

  private static final class MalformedRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
