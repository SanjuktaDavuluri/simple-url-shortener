package io.github.sanjuktadavuluri.shortener;

import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The web page (ADR 0006): a server-rendered form that works without JavaScript. It is a second
 * entry point to the same {@link LinkService} the JSON API uses, not a second implementation.
 *
 * <p>With HTMX loaded, the form posts with an {@code HX-Request} header and receives only the
 * shortener fragment; without it, the same route returns the full page. Both render one template.
 */
@Controller
class PageController {

  private static final String FULL_PAGE = "index";
  private static final String SHORTENER_FRAGMENT = "fragments/shortener :: shortener";

  /** The Expiry as the result shows it, in UTC to the minute: "2026-11-07 10:15" (spec 0004). */
  private static final DateTimeFormatter EXPIRY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final LinkService linkService;

  PageController(LinkService linkService) {
    this.linkService = linkService;
  }

  @GetMapping("/")
  String home() {
    return FULL_PAGE;
  }

  /**
   * Creates a Link from the form. A blank {@code expires_in_days} means no Lifetime; any other text
   * must be a Lifetime, checked before the Rule Set as in the API (spec 0004). Every answer keeps
   * what was typed in both fields.
   */
  @PostMapping(path = "/", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  String shorten(
      @RequestParam(name = "url", defaultValue = "") String longUrl,
      @RequestParam(name = "expires_in_days", defaultValue = "") String expiresInDays,
      @RequestHeader(name = "HX-Request", defaultValue = "false") boolean viaHtmx,
      Model model,
      HttpServletResponse response) {
    model.addAttribute("longUrl", longUrl);
    model.addAttribute("expiresInDays", expiresInDays);
    try {
      Optional<Lifetime> lifetime =
          expiresInDays.isBlank() ? Optional.empty() : Optional.of(Lifetime.parse(expiresInDays));
      Link link = linkService.create(longUrl, lifetime);
      model.addAttribute("link", link);
      link.expiry().ifPresent(expiry -> model.addAttribute("expiry", EXPIRY.format(expiry)));
    } catch (InvalidLifetimeException e) {
      model.addAttribute("lifetimeError", Lifetime.INVALID);
      response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
    } catch (RejectedLongUrlException e) {
      model.addAttribute("rejectionReason", e.rejectionReason());
      response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
    } catch (NoFreeShortCodeException e) {
      model.addAttribute("failure", LinkController.NO_FREE_SHORT_CODE);
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
    return viaHtmx ? SHORTENER_FRAGMENT : FULL_PAGE;
  }
}
