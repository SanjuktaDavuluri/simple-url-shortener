package io.github.sanjuktadavuluri.shortener.stats;

import io.github.sanjuktadavuluri.shortener.LinkStore;
import io.github.sanjuktadavuluri.shortener.LinkStore.StoredLink;
import io.github.sanjuktadavuluri.shortener.ManageTokens;
import io.github.sanjuktadavuluri.shortener.ShortenerProperties;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary;
import io.github.sanjuktadavuluri.shortener.stats.LinkStats.DayClicks;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reads a Link's Stats with its Manage Token (spec 0005). The single Stats path: the JSON API uses
 * it, and the web page will too (ADR 0006).
 *
 * <p>The presented token is checked first, in constant time and against a dummy hash when the Link
 * is unknown or has none (ADR 0023). The Click Store is touched only after a match. Every failure
 * is the same empty answer, with no reason given, so the caller can't tell an unknown Short Code
 * from a Link without a Manage Token or a wrong token (ADR 0014).
 */
@Service
public class LinkStatsService {

  /** How many UTC days the Stats' window covers: today and the 29 days before it. */
  static final int WINDOW_DAYS = 30;

  private final LinkStore links;
  private final ClickStore clicks;
  private final ShortenerProperties properties;
  private final Clock clock;

  LinkStatsService(
      LinkStore links, ClickStore clicks, ShortenerProperties properties, Clock clock) {
    this.links = links;
    this.clicks = clicks;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Returns the Stats of the Link with this Short Code if the presented token is its Manage Token,
   * Expired or not; otherwise empty, for every reason alike.
   */
  public Optional<LinkStats> statsFor(String shortCode, String presentedToken) {
    Optional<StoredLink> link = links.findForStats(shortCode);
    if (!ManageTokens.matches(presentedToken, link.flatMap(StoredLink::manageTokenHash))) {
      return Optional.empty();
    }
    StoredLink stored = link.orElseThrow();
    Instant now = clock.instant();
    LocalDate firstDay = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(WINDOW_DAYS - 1L);
    ClickSummary summary =
        clicks.summarise(stored.shortCode(), firstDay.atStartOfDay(ZoneOffset.UTC).toInstant());
    return Optional.of(
        new LinkStats(
            stored.shortCode(),
            properties.baseUrl() + "/" + stored.shortCode(),
            stored.longUrl(),
            stored.createdAt(),
            now,
            clicksPerDay(firstDay, summary),
            summary));
  }

  /** The {@value #WINDOW_DAYS} days from the first, oldest first; a day without Clicks is 0. */
  private static List<DayClicks> clicksPerDay(LocalDate firstDay, ClickSummary summary) {
    List<DayClicks> days = new ArrayList<>(WINDOW_DAYS);
    for (int offset = 0; offset < WINDOW_DAYS; offset++) {
      LocalDate day = firstDay.plusDays(offset);
      days.add(new DayClicks(day, summary.clicksPerDay().getOrDefault(day, 0L)));
    }
    return days;
  }
}
