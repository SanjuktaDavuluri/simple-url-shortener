package io.github.sanjuktadavuluri.shortener;

import io.github.sanjuktadavuluri.shortener.rules.RuleResult;
import io.github.sanjuktadavuluri.shortener.rules.RuleSet;
import org.springframework.stereotype.Service;

/**
 * Creates Links. The single create-Link path shared by the JSON API and the web page: trim the Long
 * URL, check it against the Rule Set once, draw the Link's Manage Token, then draw a Short Code and
 * save the Link with the token's hash, drawing again on a Collision (ADR 0003). The token is drawn
 * once per Link and only after the Rule Set has passed; only its hash is stored (ADR 0023).
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

  LinkService(
      RuleSet ruleSet,
      ShortCodeGenerator shortCodes,
      ManageTokenGenerator manageTokens,
      LinkStore links,
      ShortenerProperties properties) {
    this.ruleSet = ruleSet;
    this.shortCodes = shortCodes;
    this.manageTokens = manageTokens;
    this.links = links;
    this.properties = properties;
  }

  /**
   * Creates a Link for the Long URL.
   *
   * @throws RejectedLongUrlException if the Long URL breaks a Rule
   * @throws NoFreeShortCodeException if every attempt ended in a Collision
   */
  public Link create(String submittedLongUrl) {
    String longUrl = submittedLongUrl.strip();
    if (ruleSet.check(longUrl) instanceof RuleResult.Rejected rejected) {
      throw new RejectedLongUrlException(rejected.rejectionReason());
    }
    String manageToken = manageTokens.next();
    String manageTokenHash = ManageTokens.hash(manageToken);
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      String shortCode = shortCodes.next();
      try {
        links.save(shortCode, longUrl, manageTokenHash);
        return new Link(shortCode, properties.baseUrl() + "/" + shortCode, longUrl, manageToken);
      } catch (ShortCodeTakenException collision) {
        // A Collision: draw again.
      }
    }
    throw new NoFreeShortCodeException(MAX_ATTEMPTS);
  }
}
