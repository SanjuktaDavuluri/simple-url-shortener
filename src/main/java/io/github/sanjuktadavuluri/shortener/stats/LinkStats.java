package io.github.sanjuktadavuluri.shortener.stats;

import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A Link's Stats (CONTEXT.md, spec 0005): which Link it is, when the Stats were computed, and the
 * Click Summary of its stored Clicks. Bots are kept out of the Headline Click count and the last
 * Click, and reported only as bot Clicks and in the Agent Category split (ADR 0013).
 */
public record LinkStats(
    String shortCode,
    String shortUrl,
    String longUrl,
    Instant createdAt,
    Instant generatedAt,
    ClickSummary clicks) {

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
}
