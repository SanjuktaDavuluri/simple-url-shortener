package io.github.sanjuktadavuluri.shortener;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shortener configuration.
 *
 * @param baseUrl the shortener's public address; every Short URL starts with it
 * @param databasePath where the SQLite database file lives
 */
@ConfigurationProperties("shortener")
public record ShortenerProperties(String baseUrl, String databasePath) {}
