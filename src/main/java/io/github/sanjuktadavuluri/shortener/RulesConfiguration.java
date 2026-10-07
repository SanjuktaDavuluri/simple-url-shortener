package io.github.sanjuktadavuluri.shortener;

import io.github.sanjuktadavuluri.shortener.rules.RuleSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts the v1 Rule Set in force, bound to the shortener's Base URL (ADR 0004). */
@Configuration
class RulesConfiguration {

  @Bean
  RuleSet ruleSet(ShortenerProperties properties) {
    return RuleSet.v1(properties.baseUrl());
  }
}
