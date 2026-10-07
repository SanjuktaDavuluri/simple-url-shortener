package io.github.sanjuktadavuluri.shortener;

/** A newly created Link: its Short Code, its Short URL and the Long URL it points to. */
public record Link(String shortCode, String shortUrl, String longUrl) {}
