package io.github.sanjuktadavuluri.shortener.stats;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The JSON API's Stats endpoint (spec 0005): {@code GET /links/{short_code}/stats} with {@code
 * Authorization: Bearer <manage_token>}.
 *
 * <p>Only 7-character base62 paths can be Short Codes (ADR 0003), so any other path shape falls
 * through to Spring's normal {@code 404}. A missing or malformed header, an empty token, an unknown
 * Short Code, a Link without a Manage Token and a wrong token all get the one {@code 404} below,
 * byte for byte, so the endpoint never confirms which Short Codes exist (ADR 0014). Every answer is
 * {@code Cache-Control: no-store}. Nothing here logs, so neither the token nor its hash can reach a
 * log line (story 21).
 */
@RestController
class LinkStatsController {

  /** The one body every failure gets: fixed bytes, with no request path or reason in it. */
  private static final byte[] NOT_FOUND =
      """
      {"type":"about:blank","title":"Not Found","status":404,\
      "detail":"No stats found for this Short Code."}"""
          .getBytes(StandardCharsets.UTF_8);

  /** The {@code Bearer} scheme, matched case-insensitively, then a non-empty token. */
  private static final Pattern BEARER =
      Pattern.compile("bearer +(\\S+) *", Pattern.CASE_INSENSITIVE);

  /** ISO-8601 UTC to the millisecond, as in {@code links.created_at}. */
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private final LinkStatsService linkStats;

  LinkStatsController(LinkStatsService linkStats) {
    this.linkStats = linkStats;
  }

  @GetMapping("/links/{shortCode:[A-Za-z0-9]{7}}/stats")
  ResponseEntity<?> stats(
      @PathVariable String shortCode,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    return bearerToken(authorization)
        .flatMap(token -> linkStats.statsFor(shortCode, token))
        .<ResponseEntity<?>>map(
            stats ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(StatsBody.of(stats)))
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(NOT_FOUND));
  }

  /** The token of an {@code Authorization: Bearer <token>} header; empty for anything else. */
  private static Optional<String> bearerToken(String authorization) {
    if (authorization == null) {
      return Optional.empty();
    }
    Matcher bearer = BEARER.matcher(authorization);
    return bearer.matches() ? Optional.of(bearer.group(1)) : Optional.empty();
  }

  /** The {@code 200} body (spec 0005, API contract). */
  record StatsBody(
      @JsonProperty("short_code") String shortCode,
      @JsonProperty("short_url") String shortUrl,
      @JsonProperty("long_url") String longUrl,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("generated_at") String generatedAt,
      @JsonProperty("clicks") long clicks,
      @JsonProperty("bot_clicks") long botClicks,
      @JsonProperty("last_click_at") String lastClickAt,
      @JsonProperty("by_agent_category") AgentCategories byAgentCategory) {

    static StatsBody of(LinkStats stats) {
      return new StatsBody(
          stats.shortCode(),
          stats.shortUrl(),
          stats.longUrl(),
          time(stats.createdAt()),
          time(stats.generatedAt()),
          stats.headlineClicks(),
          stats.botClicks(),
          stats.lastClickAt().map(LinkStatsController::time).orElse(null),
          new AgentCategories(
              stats.byAgentCategory().get(AgentCategory.BROWSER),
              stats.byAgentCategory().get(AgentCategory.OTHER),
              stats.byAgentCategory().get(AgentCategory.BOT)));
    }
  }

  /** Every Agent Category, always present. */
  record AgentCategories(long browser, long other, long bot) {}

  private static String time(Instant instant) {
    return TIME.format(instant);
  }
}
