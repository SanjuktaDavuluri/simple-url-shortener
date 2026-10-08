package io.github.sanjuktadavuluri.shortener.clicks;

/** The Agent Category of a Click: what kind of client followed the Link (spec 0003). */
public enum AgentCategory {
  /** A web browser: a user agent that starts with {@code Mozilla/} and isn't a known bot. */
  BROWSER,
  /** A known crawler or link-preview fetcher (see {@link BotPatterns}). */
  BOT,
  /** Anything else, including no user agent and tools such as {@code curl}. */
  OTHER
}
