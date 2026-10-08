package io.github.sanjuktadavuluri.shortener.clicks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Spec 0003 seam 4: the queued Click Recorder with a stand-in Click Store (the in-app fault seam
 * R14 allows). Issue #52 (story 13): Clicks are saved in batches, one Click Store call per batch,
 * from one background writer. Issue #53 (stories 3, 14–17, 19, 20): loss is bounded, counted and
 * logged with counts only. A ticker the test moves by hand times the drop warnings, and a stand-in
 * store that blocks until released orders the writer, so no test guesses at timing.
 */
class QueuedClickRecorderTest {

  private static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");

  /** Long enough that a batch is never saved by the interval running out during a test. */
  private static final Duration LONG_FLUSH_INTERVAL = Duration.ofMinutes(5);

  /** Never reached by a passing test: only stops a recorder that waits from hanging the build. */
  private static final Duration HANG_GUARD = Duration.ofSeconds(10);

  private static final String SHORT_CODE = "Ab3xK9q";
  private static final String REFERRER_HOST = "news.example.org";
  private static final String LONG_URL = "https" + "://long.example.com/private?token=s3cret";
  private static final String USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)";

  private final SavedBatches store = new SavedBatches();
  private final AtomicLong ticker = new AtomicLong();
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
  private final Logger recorderLog = (Logger) LoggerFactory.getLogger(QueuedClickRecorder.class);

  private QueuedClickRecorder recorder;

  @BeforeEach
  void captureTheRecorderLog() {
    logged.start();
    recorderLog.addAppender(logged);
  }

  @AfterEach
  void stopTheWriter() {
    try {
      store.unblock();
      if (recorder != null) {
        recorder.stop();
      }
    } finally {
      recorderLog.detachAppender(logged);
    }
  }

  // Issue #52: batches from one background writer.

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
    recorder = unstartedRecorder(2, LONG_FLUSH_INTERVAL);

    clicks(5).forEach(recorder::record);

    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 3, 2));
  }

  @Test
  void recordingNeverThrows() {
    recorder = startedRecorder(100, 3, LONG_FLUSH_INTERVAL);

    recorder.record(null);

    assertThat(recorder.stats().dropped()).isEqualTo(1);
  }

  // Issue #53: loss is bounded, counted and logged.

  @Test
  void withTheStoreBlockedClicksBeyondTheQueueCapacityAreDroppedAndCountedWithoutWaiting() {
    store.block();
    recorder = startedRecorder(2, 1, LONG_FLUSH_INTERVAL);
    recorder.record(click(0));
    store.awaitSaveStarted();

    // Click 0 is being saved and the store won't return; the queue has room for two more.
    assertTimeoutPreemptively(HANG_GUARD, () -> clicks(1, 5).forEach(recorder::record));

    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 2, 3));
    store.unblock();
    recorder.flush();
    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(3, 2, 0));
    assertThat(store.batches().stream().flatMap(List::stream))
        .containsExactly(click(0), click(1), click(2));
  }

  @Test
  void aBatchThatFailsToSaveIsCountedAsDroppedWarnedOnceAndTheNextBatchIsSaved() {
    store.failNextSave();
    recorder = startedRecorder(100, 2, LONG_FLUSH_INTERVAL);

    clicks(0, 2).forEach(recorder::record);
    recorder.flush();
    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 2, 0));

    clicks(2, 4).forEach(recorder::record);
    recorder.flush();

    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(2, 2, 0));
    assertThat(store.batches()).containsExactly(List.of(click(2), click(3)));
    assertThat(warnings()).singleElement().asString().contains("Dropped 2 Clicks");
  }

  @Test
  void noSaveCallReceivesMoreClicksThanTheBatchSize() {
    store.block();
    recorder = startedRecorder(100, 2, LONG_FLUSH_INTERVAL);
    clicks(0, 2).forEach(recorder::record);
    store.awaitSaveStarted();
    clicks(2, 7).forEach(recorder::record);
    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 0, 7));

    store.unblock();
    recorder.flush();

    assertThat(store.batches())
        .containsExactly(
            List.of(click(0), click(1)),
            List.of(click(2), click(3)),
            List.of(click(4), click(5)),
            List.of(click(6)));
    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(7, 0, 0));
  }

  @Test
  void dropsWithinOneFlushIntervalProduceAtMostOneWarningCarryingTheAccumulatedCount() {
    recorder = unstartedRecorder(1, Duration.ofSeconds(1));
    recorder.record(click(0)); // Fills the queue: no writer takes Clicks off it.

    clicks(1, 4).forEach(recorder::record);
    ticker.addAndGet(Duration.ofMillis(999).toNanos());
    recorder.record(click(4));

    assertThat(warnings()).singleElement().asString().contains("Dropped 1 Clicks");

    ticker.addAndGet(Duration.ofMillis(1).toNanos());
    recorder.record(click(5));

    // Three drops held back within the interval, and this one.
    assertThat(warnings()).hasSize(2).last().asString().contains("Dropped 4 Clicks");
    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 5, 1));
  }

  @Test
  void dropsHeldBackWithinTheIntervalAreWarnedWhenTheWriterStops() {
    recorder = startedRecorder(100, 2, LONG_FLUSH_INTERVAL);
    store.failNextSave();
    clicks(0, 2).forEach(recorder::record);
    recorder.flush();
    store.failNextSave();
    clicks(2, 4).forEach(recorder::record);
    recorder.flush();
    assertThat(warnings()).hasSize(1);

    recorder.stop();

    assertThat(warnings()).hasSize(2).last().asString().contains("Dropped 2 Clicks");
    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 4, 0));
  }

  @Test
  void theLogCarriesCountsOnlyNeverAShortCodeReferrerUserAgentOrLongUrl() {
    store.failNextSave(new IllegalStateException(SHORT_CODE + " " + LONG_URL + " " + USER_AGENT));
    recorder = startedRecorder(1, 1, LONG_FLUSH_INTERVAL);
    recorder.record(click(0));
    recorder.flush();
    store.block();
    recorder.record(click(1));
    store.awaitSaveStarted();
    clicks(2, 4).forEach(recorder::record);
    ticker.addAndGet(LONG_FLUSH_INTERVAL.toNanos());
    recorder.record(click(4));
    store.unblock();
    recorder.stop();

    assertThat(warnings()).hasSize(2);
    assertThat(logged.list)
        .allSatisfy(
            event -> {
              assertThat(event.getFormattedMessage())
                  .doesNotContain(SHORT_CODE, REFERRER_HOST, LONG_URL, USER_AGENT, "s3cret");
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  @Test
  void theWriterLogsItsStartAndStop() {
    recorder = startedRecorder(100, 3, LONG_FLUSH_INTERVAL);
    recorder.stop();

    assertThat(logged.list)
        .filteredOn(event -> event.getLevel() == Level.INFO)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("Click writer started", "Click writer stopped");
  }

  @Test
  void recordingNeverThrowsWhenTheQueueFails() {
    recorder = recorderWith(new FailingQueue(), ticker::get);

    assertThatNoException().isThrownBy(() -> recorder.record(click(0)));

    assertThat(recorder.stats().dropped()).isEqualTo(1);
  }

  @Test
  void aClickTheQueueRefusesIsDroppedAndCounted() {
    recorder = recorderWith(new RefusingQueue(), ticker::get);

    recorder.record(click(0));

    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 1, 0));
  }

  @Test
  void recordingNeverThrowsWhenTheClockFailsAndEachDropIsCountedOnce() {
    recorder =
        recorderWith(
            new ArrayDeque<>(),
            () -> {
              throw new IllegalStateException("clock broke");
            });
    recorder.record(click(0));

    assertThatNoException().isThrownBy(() -> clicks(1, 3).forEach(recorder::record));

    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 2, 1));
  }

  @Test
  void recordingNeverThrowsWhenTheClockRunsBackwards() {
    AtomicLong backwards = new AtomicLong();
    recorder =
        recorderWith(new ArrayDeque<>(), () -> backwards.addAndGet(-Duration.ofHours(1).toNanos()));
    recorder.record(click(0));

    assertThatNoException().isThrownBy(() -> clicks(1, 4).forEach(recorder::record));

    assertThat(recorder.stats()).isEqualTo(new ClickRecorderStats(0, 3, 1));
  }

  private QueuedClickRecorder startedRecorder(
      int queueCapacity, int batchSize, Duration flushInterval) {
    QueuedClickRecorder started =
        new QueuedClickRecorder(
            store, new ArrayDeque<>(), queueCapacity, batchSize, flushInterval, ticker::get);
    started.start();
    return started;
  }

  /** A recorder whose writer never runs, so its queue only fills. */
  private QueuedClickRecorder unstartedRecorder(int queueCapacity, Duration flushInterval) {
    return new QueuedClickRecorder(
        store, new ArrayDeque<>(), queueCapacity, 500, flushInterval, ticker::get);
  }

  /** An unstarted recorder with a capacity of one, on this queue and clock. */
  private QueuedClickRecorder recorderWith(Queue<Click> queue, LongSupplier clock) {
    return new QueuedClickRecorder(store, queue, 1, 500, LONG_FLUSH_INTERVAL, clock);
  }

  private List<String> warnings() {
    synchronized (logged.list) {
      return logged.list.stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .map(ILoggingEvent::getFormattedMessage)
          .toList();
    }
  }

  private static List<Click> clicks(int count) {
    return clicks(0, count);
  }

  /** Clicks number {@code from} (inclusive) to {@code to} (exclusive), one second apart. */
  private static List<Click> clicks(int from, int to) {
    return IntStream.range(from, to).mapToObj(QueuedClickRecorderTest::click).toList();
  }

  private static Click click(int number) {
    return new Click(
        SHORT_CODE,
        NOW.plusSeconds(number),
        Optional.of(REFERRER_HOST),
        AgentCategory.BROWSER,
        DeviceClass.MOBILE);
  }

  /** A queue that fails on every call. */
  private static final class FailingQueue extends AbstractQueue<Click> {

    @Override
    public boolean offer(Click click) {
      throw new IllegalStateException("queue broke");
    }

    @Override
    public Click poll() {
      throw new IllegalStateException("queue broke");
    }

    @Override
    public Click peek() {
      throw new IllegalStateException("queue broke");
    }

    @Override
    public Iterator<Click> iterator() {
      throw new IllegalStateException("queue broke");
    }

    @Override
    public int size() {
      throw new IllegalStateException("queue broke");
    }
  }

  /** A queue that refuses every Click, as a bounded queue does when it is full. */
  private static final class RefusingQueue extends AbstractQueue<Click> {

    @Override
    public boolean offer(Click click) {
      return false;
    }

    @Override
    public Click poll() {
      return null;
    }

    @Override
    public Click peek() {
      return null;
    }

    @Override
    public Iterator<Click> iterator() {
      return Collections.emptyIterator();
    }

    @Override
    public int size() {
      return 0;
    }
  }

  /**
   * A stand-in Click Store that keeps every batch it saves. It can be told to block in its next
   * save until unblocked, or to fail its next save.
   */
  private static final class SavedBatches implements ClickStore {

    private final List<List<Click>> batches = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch saveStarted = new CountDownLatch(0);
    private volatile CountDownLatch release = new CountDownLatch(0);
    private volatile RuntimeException nextFailure;

    void block() {
      saveStarted = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }

    void unblock() {
      release.countDown();
    }

    void awaitSaveStarted() {
      assertTimeoutPreemptively(HANG_GUARD, () -> saveStarted.await());
    }

    void failNextSave() {
      failNextSave(new IllegalStateException("database is locked"));
    }

    void failNextSave(RuntimeException failure) {
      nextFailure = failure;
    }

    @Override
    public void saveAll(List<Click> clicks) {
      RuntimeException failure = nextFailure;
      if (failure != null) {
        nextFailure = null;
        throw failure;
      }
      saveStarted.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
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
      long deadline = System.nanoTime() + HANG_GUARD.toNanos();
      while (batches.size() < count) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("Expected " + count + " batches, saw " + batches.size());
        }
        Thread.onSpinWait();
      }
    }
  }
}
