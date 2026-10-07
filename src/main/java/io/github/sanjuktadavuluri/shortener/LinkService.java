package io.github.sanjuktadavuluri.shortener;

import io.github.sanjuktadavuluri.shortener.rules.RuleResult;
import io.github.sanjuktadavuluri.shortener.rules.RuleSet;
import org.springframework.stereotype.Service;

/**
 * Creates Links. The single create-Link path shared by the JSON API and the web page: trim the Long
 * URL, check it against the Rule Set once, then draw a Short Code and save the Link.
 */
@Service
public class LinkService {

  private final RuleSet ruleSet;
  private final ShortCodeGenerator shortCodes;
  private final LinkStore links;
  private final ShortenerProperties properties;

  LinkService(
      RuleSet ruleSet,
      ShortCodeGenerator shortCodes,
      LinkStore links,
      ShortenerProperties properties) {
    this.ruleSet = ruleSet;
    this.shortCodes = shortCodes;
    this.links = links;
    this.properties = properties;
  }

  /**
   * Creates a Link for the Long URL.
   *
   * @throws RejectedLongUrlException if the Long URL breaks a Rule
   */
  public Link create(String submittedLongUrl) {
    String longUrl = submittedLongUrl.strip();
    if (ruleSet.check(longUrl) instanceof RuleResult.Rejected rejected) {
      throw new RejectedLongUrlException(rejected.rejectionReason());
    }
    String shortCode = shortCodes.next();
    links.save(shortCode, longUrl);
    return new Link(shortCode, properties.baseUrl() + "/" + shortCode, longUrl);
  }
}
