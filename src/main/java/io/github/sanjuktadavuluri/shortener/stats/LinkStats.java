package io.github.sanjuktadavuluri.shortener.stats;

import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary.ReferrerHostClicks;
import io.github.sanjuktadavuluri.shortener.clicks.DeviceClass;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Link's Stats (CONTEXT.md, spec 0005): which Link it is, when the Stats were computed, the
 * non-bot Clicks on each of the 30 UTC days ending on that day (oldest first, zero-filled), and the
 * Click Summary of its stored Clicks. Bots are kept out of the Headline Click count, the per-day
 * series, the Device Class split, the Referrer Hosts and the last Click, and reported only as bot
 * Clicks and in the Agent Category split (ADR 0013).
 */
public record LinkStats(
    String shortCode,
    String shortUrl,
    String longUrl,
    Instant createdAt,
    Instant generatedAt,
    List<DayClicks> clicksPerDay,
    ClickSummary clicks) {

  public LinkStats {
    clicksPerDay = List.copyOf(clicksPerDay);
  }

  /** The Headline Click count: every stored non-bot Click ({@code browser} + {@code other}). */
  public long headlineClicks() {
    return clicks.clicks(AgentCategory.BROWSER) + clicks.clicks(AgentCategory.OTHER);
  }

  /** Every stored {@code bot} Click. */
  public long botClicks() {
    return clicks.clicks(AgentCategory.BOT);
  }

  /** The time of the latest non-bot Click, if there is one. */
  public Optional<Instant> lastClickAt() {
    return clicks.lastClickAt();
  }

  /** The number of stored Clicks in each Agent Category, every category present. */
  public Map<AgentCategory, Long> byAgentCategory() {
    return clicks.byAgentCategory();
  }

  /** The number of non-bot Clicks in each Device Class, both present. */
  public Map<DeviceClass, Long> byDeviceClass() {
    return clicks.byDeviceClass();
  }

  /** At most 10 Referrer Hosts with their non-bot Clicks, most first, then by host. */
  public List<ReferrerHostClicks> topReferrerHosts() {
    return clicks.topReferrerHosts();
  }

  /** The number of non-bot Clicks without a Referrer Host. */
  public long noReferrerHost() {
    return clicks.noReferrerHost();
  }

  /** One UTC date and its number of non-bot Clicks. */
  public record DayClicks(LocalDate date, long clicks) {

    public DayClicks {
      Objects.requireNonNull(date, "date");
    }
  }
}
