package io.github.sanjuktadavuluri.shortener;

import io.github.sanjuktadavuluri.shortener.rules.RuleResult;
import io.github.sanjuktadavuluri.shortener.rules.RuleSet;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Creates Links. The single create-Link path shared by the JSON API and the web page: trim the Long
 * URL, check it against the Rule Set once, draw the Link's Manage Token, then draw a Short Code and
 * save the Link with the token's hash and its Expiry, drawing again on a Collision (ADR 0003). The
 * token is drawn once per Link and only after the Rule Set has passed; only its hash is stored (ADR
 * 0023). The Expiry comes from the injected {@link Clock}.
 */
@Service
public class LinkService {

  /** How many Short Codes to draw before giving up (spec 0001). */
  static final int MAX_ATTEMPTS = 5;

  private final RuleSet ruleSet;
  private final ShortCodeGenerator shortCodes;
  private final ManageTokenGenerator manageTokens;
  private final LinkStore links;
  private final ShortenerProperties properties;
  private final Clock clock;
  private final MeterRegistry metrics;

  LinkService(
      RuleSet ruleSet,
      ShortCodeGenerator shortCodes,
      ManageTokenGenerator manageTokens,
      LinkStore links,
      ShortenerProperties properties,
      Clock clock,
      MeterRegistry metrics) {
    this.ruleSet = ruleSet;
    this.shortCodes = shortCodes;
    this.manageTokens = manageTokens;
    this.links = links;
    this.properties = properties;
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * Creates a Link for the Long URL, with the Expiry its Lifetime gives (none without a Lifetime).
   *
   * @throws RejectedLongUrlException if the Long URL breaks a Rule
   * @throws NoFreeShortCodeException if every attempt ended in a Collision
   */
  public Link create(String submittedLongUrl, Optional<Lifetime> lifetime) {
    String longUrl = submittedLongUrl.strip();
    if (ruleSet.check(longUrl) instanceof RuleResult.Rejected rejected) {
      metrics.counter("shortener.rejections", "rule", rejected.rule()).increment();
      throw new RejectedLongUrlException(rejected.rejectionReason());
    }
    String manageToken = manageTokens.next();
    String manageTokenHash = ManageTokens.hash(manageToken);
    // To the millisecond, the precision stored, so the Expiry returned is the one kept.
    Instant created = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Optional<Instant> expiry = lifetime.map(it -> it.expiryFrom(created));
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      String shortCode = shortCodes.next();
      try {
        links.save(shortCode, longUrl, manageTokenHash, expiry);
        metrics.counter("shortener.links.created").increment();
        return new Link(
            shortCode, properties.baseUrl() + "/" + shortCode, longUrl, manageToken, expiry);
      } catch (ShortCodeTakenException collision) {
        metrics.counter("shortener.collisions").increment();
      }
    }
    throw new NoFreeShortCodeException(MAX_ATTEMPTS);
  }
}
