package io.github.sanjuktadavuluri.shortener;

import java.time.Instant;
import java.util.Optional;

/**
 * A newly created Link: its Short Code, its Short URL, the Long URL it points to, and its Expiry
 * (none for a Link created without a Lifetime, which never expires).
 */
public record Link(String shortCode, String shortUrl, String longUrl, Optional<Instant> expiry) {}
