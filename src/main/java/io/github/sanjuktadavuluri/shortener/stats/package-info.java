/**
 * Stats (spec 0005): a Link's aggregated Clicks, readable only with its Manage Token.
 *
 * <p>The {@link io.github.sanjuktadavuluri.shortener.stats.LinkStatsService} checks the presented
 * token against the Link's stored hash in constant time (ADR 0023) and only then asks the Click
 * Store for a Click Summary. Every failure is the same empty answer, so no caller can tell an
 * unknown Short Code from a wrong token (ADR 0014).
 */
package io.github.sanjuktadavuluri.shortener.stats;
