/**
 * URL Rules (ADR 0004): independent checks that a Long URL must pass before a Link is created.
 *
 * <p>Each {@link io.github.sanjuktadavuluri.shortener.rules.Rule} is a small unit with its own
 * tests. The {@link io.github.sanjuktadavuluri.shortener.rules.RuleSet} applies them in a fixed
 * order. Add a Rule by adding a class and an entry in the Rule Set; the create-Link flow does not
 * change.
 */
package io.github.sanjuktadavuluri.shortener.rules;
