package io.github.sanjuktadavuluri.shortener.clicks;

import java.util.Objects;
import java.util.Optional;

/**
 * What the Click Classifier keeps from a Redirect's request headers: the Referrer Host (if any),
 * the Agent Category and the Device Class. It holds no raw header value (ADR 0013).
 */
public record ClickClassification(
    Optional<String> referrerHost, AgentCategory agentCategory, DeviceClass deviceClass) {

  public ClickClassification {
    Objects.requireNonNull(referrerHost, "referrerHost");
    Objects.requireNonNull(agentCategory, "agentCategory");
    Objects.requireNonNull(deviceClass, "deviceClass");
  }
}
