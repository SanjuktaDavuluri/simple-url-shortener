package io.github.sanjuktadavuluri.shortener.stats;

import io.github.sanjuktadavuluri.shortener.ShortenerProperties;
import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import io.github.sanjuktadavuluri.shortener.clicks.DeviceClass;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The Stats web page (spec 0005, ADR 0006): a second entry point to {@link LinkStatsService}, not
 * a second implementation. The token is only ever read from a {@code POST} body, never the query
 * string. Every answer is {@code Cache-Control: no-store}, and every failure is the same {@code
 * 404} form, so the page never says which Short Codes exist (ADR 0014).
 */
@Controller
class StatsPageController {

  static final String NOT_FOUND = "No stats found for that Short Code and manage token.";

  private static final String FULL_PAGE = "stats";
  private static final String FRAGMENT = "fragments/stats :: stats";
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final LinkStatsService linkStats;
  private final ShortenerProperties properties;

  StatsPageController(LinkStatsService linkStats, ShortenerProperties properties) {
    this.linkStats = linkStats;
    this.properties = properties;
  }

  @GetMapping("/stats")
  String form(
      @RequestParam(name = "short_code", defaultValue = "") String shortCode,
      Model model,
      HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    model.addAttribute("shortCode", shortCode);
    return FULL_PAGE;
  }

  @PostMapping(path = "/stats", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  String stats(
      @RequestParam(name = "short_code", defaultValue = "") String shortCode,
      @RequestParam(name = "manage_token", defaultValue = "") String manageToken,
      @RequestHeader(name = "HX-Request", defaultValue = "false") boolean viaHtmx,
      Model model,
      HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    model.addAttribute("shortCode", shortCode);
    Optional<LinkStats> found = linkStats.statsFor(shortCodeOf(shortCode), manageToken);
    if (found.isPresent()) {
      LinkStats stats = found.get();
      model.addAttribute("stats", stats);
      model.addAttribute("lastClick", stats.lastClickAt().map(TIME::format).orElse(null));
      model.addAttribute("generatedAt", TIME.format(stats.generatedAt()));
      model.addAttribute("browser", stats.byAgentCategory().get(AgentCategory.BROWSER));
      model.addAttribute("other", stats.byAgentCategory().get(AgentCategory.OTHER));
      model.addAttribute("desktop", stats.byDeviceClass().get(DeviceClass.DESKTOP));
      model.addAttribute("mobile", stats.byDeviceClass().get(DeviceClass.MOBILE));
      long max = stats.clicksPerDay().stream().mapToLong(d -> d.clicks()).max().orElse(0);
      model.addAttribute("maxPerDay", Math.max(max, 1));
    } else {
      model.addAttribute("notFound", NOT_FOUND);
      response.setStatus(HttpStatus.NOT_FOUND.value());
    }
    return viaHtmx ? FRAGMENT : FULL_PAGE;
  }

  /** A bare Short Code, or a Short URL starting with the Base URL: the code is its end. */
  private String shortCodeOf(String typed) {
    String trimmed = typed.strip();
    String prefix = properties.baseUrl() + "/";
    return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()) : trimmed;
  }
}
