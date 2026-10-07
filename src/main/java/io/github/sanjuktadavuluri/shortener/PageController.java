package io.github.sanjuktadavuluri.shortener;

import jakarta.servlet.http.HttpServletResponse;
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

  private final LinkService linkService;

  PageController(LinkService linkService) {
    this.linkService = linkService;
  }

  @GetMapping("/")
  String home() {
    return FULL_PAGE;
  }

  @PostMapping(path = "/", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  String shorten(
      @RequestParam(name = "url", defaultValue = "") String longUrl,
      @RequestHeader(name = "HX-Request", defaultValue = "false") boolean viaHtmx,
      Model model,
      HttpServletResponse response) {
    model.addAttribute("longUrl", longUrl);
    try {
      model.addAttribute("link", linkService.create(longUrl));
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
