package io.github.sanjuktadavuluri.shortener.clicks;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A Click Summary: one Link's stored Clicks, aggregated by the Click Store for its Stats (spec
 * 0005). It holds the number of Clicks in each Agent Category (every category present, zero if it
 * has none). Over non-bot Clicks only (ADR 0013), it also holds the number in each Device Class
 * (both present), the top 10 Referrer Hosts (most Clicks first, then by host), the number with no
 * Referrer Host, the number on each UTC day from the window start that has any (oldest first), and
 * the time of the latest one, if there is one.
 */
public record ClickSummary(
    Map<AgentCategory, Long> byAgentCategory,
    Map<DeviceClass, Long> byDeviceClass,
    List<ReferrerHostClicks> topReferrerHosts,
    long noReferrerHost,
    Map<LocalDate, Long> clicksPerDay,
    Optional<Instant> lastClickAt) {

  /** At most this many Referrer Hosts are in the top list. */
  public static final int TOP_REFERRER_HOSTS = 10;

  public ClickSummary {
    Map<AgentCategory, Long> categories = new EnumMap<>(AgentCategory.class);
    for (AgentCategory category : AgentCategory.values()) {
      categories.put(category, byAgentCategory.getOrDefault(category, 0L));
    }
    byAgentCategory = Collections.unmodifiableMap(categories);
    Map<DeviceClass, Long> devices = new EnumMap<>(DeviceClass.class);
    for (DeviceClass deviceClass : DeviceClass.values()) {
      devices.put(deviceClass, byDeviceClass.getOrDefault(deviceClass, 0L));
    }
    byDeviceClass = Collections.unmodifiableMap(devices);
    topReferrerHosts = List.copyOf(topReferrerHosts);
    clicksPerDay = Collections.unmodifiableSortedMap(new TreeMap<>(clicksPerDay));
    Objects.requireNonNull(lastClickAt, "lastClickAt");
  }

  /** The summary of a Link with no stored Clicks: zero everywhere, empty lists, no last Click. */
  public static ClickSummary none() {
    return new ClickSummary(Map.of(), Map.of(), List.of(), 0, Map.of(), Optional.empty());
  }

  /** The number of stored Clicks in this Agent Category. */
  public long clicks(AgentCategory category) {
    return byAgentCategory.get(category);
  }

  /** A Referrer Host and its number of non-bot Clicks. */
  public record ReferrerHostClicks(String host, long clicks) {

    public ReferrerHostClicks {
      Objects.requireNonNull(host, "host");
    }
  }
}
