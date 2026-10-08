package io.github.sanjuktadavuluri.shortener.clicks;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The queued Click Recorder (ADR 0012, spec 0003): a bounded in-memory queue drained by one
 * background writer that saves Clicks in batches, one Click Store call per batch.
 *
 * <p>{@link #record} offers the Click to the queue and never waits: when the queue is full the
 * Click is dropped and counted. The writer takes up to <em>batch size</em> Clicks, waiting at most
 * the <em>flush interval</em> for a batch to fill. A batch that fails to save is counted as dropped
 * and the writer carries on. The writer never holds the lock while it saves, so a Redirect handing
 * over a Click never waits for the database.
 *
 * <p>It runs as a {@link SmartLifecycle} bean in a phase that starts before the web server and
 * stops after it.
 */
public final class QueuedClickRecorder implements ClickRecorder, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(QueuedClickRecorder.class);

  /** Below the web server's phases, so the writer starts before and stops after the server. */
  private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

  private final ClickStore store;
  private final int queueCapacity;
  private final int batchSize;
  private final long flushIntervalNanos;

  private final ReentrantLock lock = new ReentrantLock();

  /** Signals the writer: a batch filled up, the first Click arrived, a flush or a stop. */
  private final Condition work = lock.newCondition();

  /** Signals flushes: the writer finished a batch, or stopped. */
  private final Condition batchDone = lock.newCondition();

  // Guarded by lock.
  private final ArrayDeque<Click> queue;
  private long accepted;
  private long finished;
  private long recorded;
  private long dropped;
  private int inFlight;
  private int flushesWaiting;
  private boolean running;
  private Thread writer;

  public QueuedClickRecorder(
      ClickStore store, int queueCapacity, int batchSize, Duration flushInterval) {
    if (queueCapacity < 1 || batchSize < 1 || !flushInterval.isPositive()) {
      throw new IllegalArgumentException(
          "Queue capacity, batch size and flush interval must be positive");
    }
    this.store = Objects.requireNonNull(store, "store");
    this.queueCapacity = queueCapacity;
    this.batchSize = batchSize;
    this.flushIntervalNanos = flushInterval.toNanos();
    this.queue = new ArrayDeque<>(Math.min(queueCapacity, 1024));
  }

  @Override
  public void record(Click click) {
    try {
      Objects.requireNonNull(click, "click");
      lock.lock();
      try {
        if (queue.size() >= queueCapacity) {
          dropped++;
          return;
        }
        queue.offer(click);
        accepted++;
        if (queue.size() == 1 || queue.size() >= batchSize) {
          work.signal();
        }
      } finally {
        lock.unlock();
      }
    } catch (RuntimeException e) {
      countDropped();
    }
  }

  private void countDropped() {
    lock.lock();
    try {
      dropped++;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Saves every Click recorded so far, then returns. Used by tests (instead of sleeping) and by
   * shutdown. Returns at once if the writer isn't running.
   */
  public void flush() {
    lock.lock();
    try {
      long target = accepted;
      flushesWaiting++;
      work.signal();
      try {
        while (running && finished < target) {
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
      return new ClickRecorderStats(recorded, dropped, queue.size() + (long) inFlight);
    } finally {
      lock.unlock();
    }
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

  @Override
  public void stop() {
    Thread stopping;
    lock.lock();
    try {
      if (!running) {
        return;
      }
      running = false;
      stopping = writer;
      writer = null;
      work.signalAll();
      batchDone.signalAll();
    } finally {
      lock.unlock();
    }
    try {
      stopping.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info("Click writer stopped");
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
      List<Click> batch = nextBatch();
      while (!batch.isEmpty()) {
        save(batch);
        batch = nextBatch();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for the first Click, then up to the flush interval for the batch to fill (no longer than
   * it takes a flush to be asked for). Returns an empty batch when the recorder stops.
   */
  private List<Click> nextBatch() throws InterruptedException {
    lock.lock();
    try {
      while (running && queue.isEmpty()) {
        work.await();
      }
      if (!running) {
        return List.of();
      }
      long deadline = System.nanoTime() + flushIntervalNanos;
      while (running && flushesWaiting == 0 && queue.size() < batchSize) {
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
      inFlight = batch.size();
      return batch;
    } finally {
      lock.unlock();
    }
  }

  /** Saves one batch with one Click Store call; a failure costs the batch, never the writer. */
  private void save(List<Click> batch) {
    boolean saved;
    try {
      store.saveAll(batch);
      saved = true;
    } catch (RuntimeException e) {
      saved = false;
      // Counts and the failure's type only: never a Click's contents (ADR 0013).
      log.warn(
          "Dropped {} Clicks: the batch failed to save ({})",
          batch.size(),
          e.getClass().getSimpleName());
    }
    lock.lock();
    try {
      if (saved) {
        recorded += batch.size();
      } else {
        dropped += batch.size();
      }
      finished += batch.size();
      inFlight = 0;
      batchDone.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
