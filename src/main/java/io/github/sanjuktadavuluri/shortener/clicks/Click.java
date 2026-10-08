package io.github.sanjuktadavuluri.shortener.clicks;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * A Click: one successful Redirect of one Link (CONTEXT.md, spec 0003). It keeps only the Short
 * Code, the UTC time the Redirect was served, the Referrer Host and the Agent Category and Device
 * Class; never a raw header, an IP address or a full referrer URL (ADR 0013).
 *
 * <p>The time is kept to the millisecond, the precision the Click Store saves it with.
 */
public record Click(
    String shortCode,
    Instant clickedAt,
    Optional<String> referrerHost,
    AgentCategory agentCategory,
    DeviceClass deviceClass) {

  public Click {
    Objects.requireNonNull(shortCode, "shortCode");
    clickedAt = Objects.requireNonNull(clickedAt, "clickedAt").truncatedTo(ChronoUnit.MILLIS);
    Objects.requireNonNull(referrerHost, "referrerHost");
    Objects.requireNonNull(agentCategory, "agentCategory");
    Objects.requireNonNull(deviceClass, "deviceClass");
  }

  /** The Click for a Redirect of this Short Code at this time, with its classified headers. */
  public static Click of(String shortCode, Instant clickedAt, ClickClassification classification) {
    return new Click(
        shortCode,
        clickedAt,
        classification.referrerHost(),
        classification.agentCategory(),
        classification.deviceClass());
  }
}
