package io.github.sanjuktadavuluri.shortener.clicks;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Click Classifier (spec 0003): reduces the raw {@code Referer} and {@code User-Agent} header
 * values of a Redirect to the non-personal attributes a Click keeps (ADR 0013).
 *
 * <p>It is pure: no Spring, no I/O and no logging. The raw values exist only for the duration of
 * the call and are never stored or logged.
 */
public final class ClickClassifier {

  private static final String BROWSER_PREFIX = "Mozilla/";

  /** Mobile markers, matched as written (the conventional {@code Mobi} check plus platforms). */
  private static final List<String> MOBILE_MARKERS = List.of("Mobi", "Android", "iPhone", "iPad");

  private ClickClassifier() {}

  /**
   * Classifies one Redirect's request headers.
   *
   * @param referer the raw {@code Referer} header value, or {@code null} if absent
   * @param userAgent the raw {@code User-Agent} header value, or {@code null} if absent
   */
  public static ClickClassification classify(String referer, String userAgent) {
    return new ClickClassification(
        referrerHost(referer), agentCategory(userAgent), deviceClass(userAgent));
  }

  /**
   * The Agent Category, checked in order: a known bot pattern first (even when the user agent
   * starts with {@code Mozilla/}), then a browser, otherwise other.
   */
  static AgentCategory agentCategory(String userAgent) {
    if (BotPatterns.matches(userAgent)) {
      return AgentCategory.BOT;
    }
    if (userAgent != null && userAgent.startsWith(BROWSER_PREFIX)) {
      return AgentCategory.BROWSER;
    }
    return AgentCategory.OTHER;
  }

  /** The Device Class: mobile with a mobile marker, otherwise desktop (including unknowns). */
  static DeviceClass deviceClass(String userAgent) {
    if (userAgent != null && MOBILE_MARKERS.stream().anyMatch(userAgent::contains)) {
      return DeviceClass.MOBILE;
    }
    return DeviceClass.DESKTOP;
  }

  /**
   * The Referrer Host: the host of a valid {@code http}/{@code https} Referer, in lower case, with
   * no port, user info, path or query. Anything else gives none.
   */
  static Optional<String> referrerHost(String referer) {
    if (referer == null) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = new URI(referer.strip());
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      return Optional.empty();
    }
    return Optional.ofNullable(uri.getHost())
        .filter(host -> !host.isEmpty())
        .map(host -> host.toLowerCase(Locale.ROOT));
  }
}
