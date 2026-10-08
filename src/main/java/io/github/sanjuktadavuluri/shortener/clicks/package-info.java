/**
 * Clickstream (spec 0003): what a successful Redirect records as a Click.
 *
 * <p>The {@link io.github.sanjuktadavuluri.shortener.clicks.ClickClassifier} reduces the raw {@code
 * Referer} and {@code User-Agent} headers to a Referrer Host, an Agent Category and a Device Class.
 * Nothing that could identify a person is kept (ADR 0013).
 */
package io.github.sanjuktadavuluri.shortener.clicks;
