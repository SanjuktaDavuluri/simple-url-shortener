package io.github.sanjuktadavuluri.shortener.clicks;

/**
 * The Click Recorder's running counts since it was created (spec 0003; published as metrics by
 * R12).
 *
 * @param recorded Clicks saved to the Click Store
 * @param dropped Clicks lost: refused by a full queue, or in a batch that failed to save
 * @param pending Clicks taken but not yet saved or dropped
 */
public record ClickRecorderStats(long recorded, long dropped, long pending) {}
