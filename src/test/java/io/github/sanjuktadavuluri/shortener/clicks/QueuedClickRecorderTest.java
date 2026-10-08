package io.github.sanjuktadavuluri.shortener.clicks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #52 (spec 0003 story 13): the queued Click Recorder saves Clicks in batches, one Click
 * Store call per batch, from its one background writer. A stand-in Click Store records each call.
 */
class QueuedClickRecorderTest {

  private static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");

  /** Long enough that a batch is never saved by the interval running out during a test. */
  private static final Duration LONG_FLUSH_INTERVAL = Duration.ofMinutes(5);

  private final SavedBatches store = new SavedBatches();

  private QueuedClickRecorder recorder;

  @AfterEach
  void stopTheWriter() {
    if (recorder != null) {
      recorder.stop();
    }
  }

  @Test
  void flushSavesEveryRecordedClickInBatchesNoLargerThanTheBatchSize() {
    recorder = startedRecorder(100, 3, LONG_FLUSH_INTERVAL);
    List<Click> clicks = clicks(7);

    clicks.forEach(recorder::record);
    recorder.flush();

    assertThat(store.batches()).allSatisfy(batch -> assertThat(batch).hasSizeBetween(1, 3));
    assertThat(store.batches().stream().flatMap(List::stream)).containsExactlyElementsOf(clicks);
    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(7, 0, 0));
  }

  @Test
  void aFullBatchIsSavedWithOneClickStoreCallWithoutWaitingForTheFlushInterval() {
    recorder = startedRecorder(100, 3, LONG_FLUSH_INTERVAL);
    List<Click> clicks = clicks(3);

    clicks.forEach(recorder::record);

    store.awaitBatches(1);
    assertThat(store.batches()).containsExactly(clicks);
  }

  @Test
  void aPartBatchIsSavedOnceTheFlushIntervalHasPassed() {
    recorder = startedRecorder(100, 500, Duration.ofMillis(50));
    List<Click> clicks = clicks(2);

    clicks.forEach(recorder::record);

    store.awaitBatches(1);
    assertThat(store.batches()).containsExactly(clicks);
  }

  @Test
  void clicksArrivingWhileTheQueueIsFullAreDroppedAndCounted() {
    recorder = new QueuedClickRecorder(store, 2, 500, LONG_FLUSH_INTERVAL);
    // Not started: nothing takes Clicks off the queue, so it fills up.

    clicks(5).forEach(recorder::record);

    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 3, 2));
  }

  @Test
  void recordingNeverThrows() {
    recorder = startedRecorder(100, 3, LONG_FLUSH_INTERVAL);

    recorder.record(null);

    assertThat(recorder.stats().dropped()).isEqualTo(1);
  }

  private QueuedClickRecorder startedRecorder(
      int queueCapacity, int batchSize, Duration flushInterval) {
    QueuedClickRecorder started =
        new QueuedClickRecorder(store, queueCapacity, batchSize, flushInterval);
    started.start();
    return started;
  }

  private static List<Click> clicks(int count) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                new Click(
                    "Ab3xK9q",
                    NOW.plusSeconds(i),
                    Optional.empty(),
                    AgentCategory.BROWSER,
                    DeviceClass.DESKTOP))
        .toList();
  }

  /** A stand-in Click Store that keeps every batch it is asked to save. */
  private static final class SavedBatches implements ClickStore {

    private final List<List<Click>> batches = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void saveAll(List<Click> clicks) {
      batches.add(List.copyOf(clicks));
    }

    @Override
    public List<Click> listClicks(String shortCode) {
      throw new UnsupportedOperationException();
    }

    List<List<Click>> batches() {
      synchronized (batches) {
        return List.copyOf(batches);
      }
    }

    void awaitBatches(int count) {
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (batches.size() < count) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("Expected " + count + " batches, saw " + batches.size());
        }
        Thread.onSpinWait();
      }
    }
  }
}
