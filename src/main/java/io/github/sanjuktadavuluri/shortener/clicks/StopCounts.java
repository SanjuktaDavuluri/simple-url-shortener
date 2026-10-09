package io.github.sanjuktadavuluri.shortener.clicks;

/**
 * What the Click Recorder's last {@code stop()} did (spec 0006 story 37).
 *
 * @param flushed Clicks saved to the Click Store while stopping
 * @param dropped Clicks lost while stopping: unsaved when the shutdown timeout passed, or in a
 *     batch that failed to save
 */
public record StopCounts(long flushed, long dropped) {}
