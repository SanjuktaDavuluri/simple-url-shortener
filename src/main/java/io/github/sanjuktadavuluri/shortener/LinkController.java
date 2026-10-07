package io.github.sanjuktadavuluri.shortener;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The JSON API for creating Links. */
@RestController
class LinkController {

  private final ShortCodeGenerator shortCodes;
  private final ShortenerProperties properties;
  private final LinkStore links;

  LinkController(ShortCodeGenerator shortCodes, LinkStore links, ShortenerProperties properties) {
    this.shortCodes = shortCodes;
    this.links = links;
    this.properties = properties;
  }

  @PostMapping("/links")
  @ResponseStatus(HttpStatus.CREATED)
  CreatedLink createLink(@RequestBody CreateLinkRequest request) {
    String shortCode = shortCodes.next();
    links.save(shortCode, request.url());
    return new CreatedLink(shortCode, properties.baseUrl() + "/" + shortCode, request.url());
  }

  @GetMapping("/{shortCode}")
  ResponseEntity<Void> followLink(@PathVariable String shortCode) {
    return links
        .findLongUrl(shortCode)
        .map(
            longUrl ->
                ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(longUrl))
                    .header("Cache-Control", "no-store")
                    .<Void>build())
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  record CreateLinkRequest(String url) {}

  record CreatedLink(
      @JsonProperty("short_code") String shortCode,
      @JsonProperty("short_url") String shortUrl,
      @JsonProperty("long_url") String longUrl) {}
}
