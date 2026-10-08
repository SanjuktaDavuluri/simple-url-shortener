package io.github.sanjuktadavuluri.shortener;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickClassifier;
import io.github.sanjuktadavuluri.shortener.clicks.ClickRecorder;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

  static final String MALFORMED_REQUEST =
      "The request body must be JSON like {\"url\": \"https://example.com\"}.";

  static final String NO_FREE_SHORT_CODE = "Couldn't find a free Short Code. Please try again.";

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
    Link link = linkService.create(request.url());
    return new CreatedLink(link.shortCode(), link.shortUrl(), link.longUrl());
  }

  /**
   * Follows a Short URL. Only 7-character base62 paths can be Short Codes (ADR 0003), so other
   * single-segment paths such as {@code /favicon.ico} fall through to static resources.
   *
   * <p>This is the single point where Clicks are produced (spec 0003, ADR 0005): a successful
   * {@code GET} hands one Click to the Click Recorder, which returns at once, so the {@code 302} is
   * the same as before. A {@code 404} or a {@code HEAD} request records nothing. The raw {@code
   * Referer} and {@code User-Agent} only reach the Click Classifier (ADR 0013).
   */
  @GetMapping("/{shortCode:[A-Za-z0-9]{7}}")
  ResponseEntity<Void> followLink(
      @PathVariable String shortCode,
      HttpMethod method,
      @RequestHeader(name = HttpHeaders.REFERER, required = false) String referer,
      @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
    return links
        .findLongUrl(shortCode)
        .map(
            longUrl -> {
              if (HttpMethod.GET.equals(method)) {
                clickRecorder.record(
                    Click.of(
                        shortCode, clock.instant(), ClickClassifier.classify(referer, userAgent)));
              }
              return ResponseEntity.status(HttpStatus.FOUND)
                  .location(URI.create(longUrl))
                  .header("Cache-Control", "no-store")
                  .<Void>build();
            })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @ExceptionHandler
  ProblemDetail rejected(RejectedLongUrlException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.rejectionReason());
  }

  @ExceptionHandler
  ProblemDetail noFreeShortCode(NoFreeShortCodeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, NO_FREE_SHORT_CODE);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MalformedRequestException.class})
  ProblemDetail malformed() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, MALFORMED_REQUEST);
  }

  record CreateLinkRequest(String url) {}

  record CreatedLink(
      @JsonProperty("short_code") String shortCode,
      @JsonProperty("short_url") String shortUrl,
      @JsonProperty("long_url") String longUrl) {}

  private static final class MalformedRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
