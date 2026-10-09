package io.github.sanjuktadavuluri.shortener.clicks;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Click Summary: one Link's stored Clicks, aggregated by the Click Store for its Stats (spec
 * 0005). It holds the number of Clicks in each Agent Category (every category present, zero if it
 * has none) and the time of the latest non-bot Click, if there is one (ADR 0013).
 */
public record ClickSummary(
    Map<AgentCategory, Long> byAgentCategory, Optional<Instant> lastClickAt) {

  public ClickSummary {
    Map<AgentCategory, Long> counts = new EnumMap<>(AgentCategory.class);
    for (AgentCategory category : AgentCategory.values()) {
      counts.put(category, byAgentCategory.getOrDefault(category, 0L));
    }
    byAgentCategory = Collections.unmodifiableMap(counts);
    Objects.requireNonNull(lastClickAt, "lastClickAt");
  }

  /** The summary of a Link with no stored Clicks: zero in every Agent Category, no last Click. */
  public static ClickSummary none() {
    return new ClickSummary(Map.of(), Optional.empty());
  }

  /** The number of stored Clicks in this Agent Category. */
  public long clicks(AgentCategory category) {
    return byAgentCategory.get(category);
  }
}
