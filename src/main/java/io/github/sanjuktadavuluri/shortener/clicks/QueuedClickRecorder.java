package io.github.sanjuktadavuluri.shortener.clicks;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The queued Click Recorder (ADR 0012, spec 0003): a bounded in-memory queue drained by one
 * background writer that saves Clicks in batches, one Click Store call per batch.
 *
 * <p>{@link #record} offers the Click to the queue and never waits or throws: when the queue is
 * full, or anything inside it fails, the Click is dropped and counted. The writer takes up to
 * <em>batch size</em> Clicks, waiting at most the <em>flush interval</em> for a batch to fill. A
 * batch that fails to save is counted as dropped and the writer carries on with the next one; there
 * is no retry. The writer never holds the lock while it saves or logs, so a Redirect handing over a
 * Click never waits for the database or the log.
 *
 * <p>Drops are logged as a warning carrying counts only, never a Click's contents (ADR 0013, ADR
 * 0015), and at most one line per flush interval: drops in between are held back and carried by the
 * next line, which the writer logs once the interval has passed, or on stop.
 *
 * <p>It runs as a {@link SmartLifecycle} bean in a phase that starts before the web server and
 * stops after it, so Redirects served while the server drains are still recorded. On stop the
 * writer saves everything queued, for at most the <em>shutdown timeout</em>; Clicks still unsaved
 * then are counted as dropped and logged (counts only), and the writer is left to end (spec 0003
 * story 18).
 */
public final class QueuedClickRecorder implements ClickRecorder, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(QueuedClickRecorder.class);

  /** Below the web server's phases, so the writer starts before and stops after the server. */
  private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

  private final ClickStore store;
  private final int queueCapacity;
  private final int batchSize;
  private final long flushIntervalNanos;
  private final Duration shutdownTimeout;

  /** Times the drop warnings, in nanoseconds ({@code System::nanoTime} in production). */
  private final LongSupplier ticker;

  private final ReentrantLock lock = new ReentrantLock();

  /** Signals the writer: a batch filled up, the first Click arrived, a flush or a stop. */
  private final Condition work = lock.newCondition();

  /** Signals flushes: the writer finished a batch, or stopped. */
  private final Condition batchDone = lock.newCondition();

  // Guarded by lock.
  private final Queue<Click> queue;
  private long accepted;
  private long finished;

  /**
   * Like {@code finished}, but only once any warning for the batch is logged: flushes wait on it.
   */
  private long settled;

  private long recorded;
  private long dropped;
  private int flushesWaiting;
  private boolean running;

  /**
   * Set while stop waits for the writer to save what is queued: the writer ends once it is empty.
   */
  private boolean stopping;

  /**
   * The thread saving batches. A writer that is no longer this one was given up on by stop, which
   * already counted its batch as dropped.
   */
  private Thread writer;

  /** Drops not yet carried by a warning line. */
  private long unwarnedDrops;

  private boolean warnedBefore;
  private long lastWarningAt;

  /** What the last stop saved and dropped. Guarded by lock. */
  private StopCounts lastStop = new StopCounts(0, 0);

  public QueuedClickRecorder(
      ClickStore store,
      int queueCapacity,
      int batchSize,
      Duration flushInterval,
      Duration shutdownTimeout) {
    this(
        store,
        new ArrayDeque<>(Math.min(queueCapacity, 1024)),
        queueCapacity,
        batchSize,
        flushInterval,
        shutdownTimeout,
        System::nanoTime);
  }

  /**
   * The fault seam for unit tests (spec 0003 seam 4): the queue the Clicks wait in and the ticker
   * that times the drop warnings.
   */
  QueuedClickRecorder(
      ClickStore store,
      Queue<Click> queue,
      int queueCapacity,
      int batchSize,
      Duration flushInterval,
      Duration shutdownTimeout,
      LongSupplier ticker) {
    if (queueCapacity < 1 || batchSize < 1 || !flushInterval.isPositive()) {
      throw new IllegalArgumentException(
          "Queue capacity, batch size and flush interval must be positive");
    }
    if (shutdownTimeout.isNegative()) {
      throw new IllegalArgumentException("Shutdown timeout must not be negative");
    }
    this.store = Objects.requireNonNull(store, "store");
    this.queue = Objects.requireNonNull(queue, "queue");
    this.queueCapacity = queueCapacity;
    this.batchSize = batchSize;
    this.flushIntervalNanos = flushInterval.toNanos();
    this.shutdownTimeout = shutdownTimeout;
    this.ticker = Objects.requireNonNull(ticker, "ticker");
  }

  @Override
  public void record(Click click) {
    boolean taken = false;
    try {
      Objects.requireNonNull(click, "click");
      lock.lock();
      try {
        if (queue.size() < queueCapacity && queue.offer(click)) {
          accepted++;
          taken = true;
          if (queue.size() == 1 || queue.size() >= batchSize) {
            work.signal();
          }
        }
      } finally {
        lock.unlock();
      }
    } catch (RuntimeException e) {
      // Counted as dropped below, unless the queue already took the Click.
    }
    if (!taken) {
      countDropped(1);
      warnIfDue();
    }
  }

  private void countDropped(long count) {
    lock.lock();
    try {
      dropped += count;
      unwarnedDrops += count;
    } finally {
      lock.unlock();
    }
  }

  /** Clicks taken and not yet saved or dropped: queued, or in the batch being saved. */
  private long pending() {
    return accepted - finished;
  }

  /**
   * Logs the drops held back, if any, unless a line was logged less than a flush interval ago.
   * Never throws: if the ticker fails, the drops stay counted and wait for the next line.
   */
  private void warnIfDue() {
    long count;
    lock.lock();
    try {
      if (unwarnedDrops == 0) {
        return;
      }
      long now = ticker.getAsLong();
      long sinceLastWarning = now - lastWarningAt;
      if (warnedBefore && sinceLastWarning < 0) {
        lastWarningAt = now; // The ticker ran backwards: start the interval again from here.
        return;
      }
      if (warnedBefore && sinceLastWarning < flushIntervalNanos) {
        return;
      }
      warnedBefore = true;
      lastWarningAt = now;
      count = unwarnedDrops;
      unwarnedDrops = 0;
    } catch (RuntimeException e) {
      return;
    } finally {
      lock.unlock();
    }
    warn(count);
  }

  /** Logs any drops held back, whenever the last line was. Used on stop. */
  private void warnHeldBackDrops() {
    long count;
    lock.lock();
    try {
      count = unwarnedDrops;
      unwarnedDrops = 0;
    } finally {
      lock.unlock();
    }
    if (count > 0) {
      warn(count);
    }
  }

  private static void warn(long count) {
    try {
      // Counts only: never a Short Code, referrer, user agent or Long URL (ADR 0013, ADR 0015).
      log.warn(
          "Dropped {} Clicks since the last warning: queue full or batch failed to save", count);
    } catch (RuntimeException e) {
      // A failing log never breaks recording.
    }
  }

  /**
   * Saves every Click recorded so far, then returns. Used by tests instead of sleeping. Returns at
   * once if the writer isn't running.
   */
  public void flush() {
    lock.lock();
    try {
      long target = accepted;
      flushesWaiting++;
      work.signal();
      try {
        while (running && settled < target) {
          batchDone.awaitUninterruptibly();
        }
      } finally {
        flushesWaiting--;
      }
    } finally {
      lock.unlock();
    }
  }

  /** The running counts of recorded, dropped and pending Clicks. */
  public ClickRecorderStats stats() {
    lock.lock();
    try {
      return new ClickRecorderStats(recorded, dropped, pending());
    } finally {
      lock.unlock();
    }
  }

  /** The Clicks the last {@link #stop()} saved and dropped; zero before any stop. */
  public StopCounts lastStop() {
    lock.lock();
    try {
      return lastStop;
    } finally {
      lock.unlock();
    }
  }

  /**
   * The most Clicks the queue holds before new ones are dropped: readiness compares pending Clicks
   * with it (spec 0006).
   */
  public int queueCapacity() {
    return queueCapacity;
  }

  @Override
  public void start() {
    lock.lock();
    try {
      if (running) {
        return;
      }
      running = true;
      writer = Thread.ofPlatform().name("click-writer").daemon().start(this::writeBatches);
    } finally {
      lock.unlock();
    }
    log.info("Click writer started");
  }

  /**
   * Lets the writer save everything queued, waiting at most the shutdown timeout. Clicks still
   * unsaved then, queued or in the batch being saved, are counted as dropped with one warning, and
   * the writer is interrupted and left to end: whatever its save does, it no longer counts.
   */
  @Override
  public void stop() {
    Thread draining;
    long recordedBefore;
    long droppedBefore;
    lock.lock();
    try {
      if (!running || stopping) {
        return;
      }
      recordedBefore = recorded;
      droppedBefore = dropped;
      stopping = true;
      draining = writer;
      work.signalAll();
    } finally {
      lock.unlock();
    }
    boolean drained = awaitEnd(draining);
    long unsaved;
    lock.lock();
    try {
      unsaved = drained ? 0 : pending();
      if (!drained) {
        dropped += unsaved;
        finished = accepted;
        settled = accepted;
        queue.clear();
      }
      lastStop = new StopCounts(recorded - recordedBefore, dropped - droppedBefore);
      running = false;
      stopping = false;
      writer = null;
      work.signalAll();
      batchDone.signalAll();
    } finally {
      lock.unlock();
    }
    if (!drained) {
      draining.interrupt();
    }
    warnHeldBackDrops();
    if (unsaved > 0) {
      warnUnsavedOnStop(unsaved);
    }
    log.info("Click writer stopped");
  }

  /** Waits up to the shutdown timeout for the writer to end; true if it did. */
  private boolean awaitEnd(Thread draining) {
    try {
      return draining.join(shutdownTimeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return !draining.isAlive();
    }
  }

  private void warnUnsavedOnStop(long count) {
    try {
      // Counts only: never a Short Code, referrer, user agent or Long URL (ADR 0013, ADR 0015).
      log.warn(
          "Dropped {} Clicks still unsaved when the shutdown timeout of {} passed",
          count,
          shutdownTimeout);
    } catch (RuntimeException e) {
      // A failing log never breaks shutdown.
    }
  }

  @Override
  public boolean isRunning() {
    lock.lock();
    try {
      return running;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  private void writeBatches() {
    try {
      for (Optional<List<Click>> batch = nextBatch(); batch.isPresent(); batch = nextBatch()) {
        if (!batch.get().isEmpty()) {
          save(batch.get());
        }
        warnIfDue();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for the first Click, then up to the flush interval for the batch to fill (no longer than
   * it takes a flush or a stop to be asked for). While drops are held back it wakes at least once
   * per flush interval, with an empty batch, so their warning is logged. Returns nothing when the
   * recorder is stopping and the queue is empty, or when stop gave up on this writer.
   */
  private Optional<List<Click>> nextBatch() throws InterruptedException {
    lock.lock();
    try {
      while (isCurrentWriter() && !stopping && queue.isEmpty()) {
        if (unwarnedDrops > 0) {
          work.awaitNanos(flushIntervalNanos);
          if (queue.isEmpty()) {
            return isCurrentWriter() && !stopping ? Optional.of(List.of()) : Optional.empty();
          }
        } else {
          work.await();
        }
      }
      if (!isCurrentWriter() || queue.isEmpty()) {
        return Optional.empty();
      }
      long deadline = System.nanoTime() + flushIntervalNanos;
      while (!stopping && flushesWaiting == 0 && queue.size() < batchSize) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          break;
        }
        work.awaitNanos(remaining);
      }
      List<Click> batch = new ArrayList<>(Math.min(batchSize, queue.size()));
      while (batch.size() < batchSize && !queue.isEmpty()) {
        batch.add(queue.poll());
      }
      return Optional.of(batch);
    } finally {
      lock.unlock();
    }
  }

  /** Guarded by lock. False once stop has given up on this thread, or the recorder has stopped. */
  private boolean isCurrentWriter() {
    return writer == Thread.currentThread();
  }

  /**
   * Saves one batch with one Click Store call. A failure costs the batch, never the writer: its
   * Clicks are counted as dropped and warned about before a waiting flush returns. A save that ends
   * after stop gave up on the writer is not counted: stop already counted it as dropped.
   */
  private void save(List<Click> batch) {
    boolean saved;
    try {
      store.saveAll(batch);
      saved = true;
    } catch (RuntimeException e) {
      saved = false;
    }
    lock.lock();
    try {
      if (!isCurrentWriter()) {
        return;
      }
      if (saved) {
        recorded += batch.size();
      } else {
        dropped += batch.size();
        unwarnedDrops += batch.size();
      }
      finished += batch.size();
    } finally {
      lock.unlock();
    }
    if (!saved) {
      warnIfDue();
    }
    lock.lock();
    try {
      settled += batch.size();
      batchDone.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
