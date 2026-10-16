package network.vonix.threadedhorizons.common.chunkio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * In-flight save tracking without a retained future queue.
 * Admission is fail-closed. Completions decrement themselves, so the
 * server tick does not walk outstanding futures.
 *
 * <p><b>Durability contract.</b> {@link #joinFully(long)} is the barrier a
 * shutdown/unload path must use. It waits for the in-flight count to reach
 * zero and then asserts that the count <em>stayed</em> zero across a short
 * quiet window. The quiet window exists because a save can enqueue additional
 * storage work from a completion callback; a bare zero-read can observe a
 * momentary gap and report "idle" while follow-on writes are still being
 * scheduled. Reporting a false idle is what let buffered region writes survive
 * past {@code Stopping server} and land during process exit, losing any writes
 * that had not drained.
 */
public final class SaveFutureTracker {

    public static final int MAX_SAVE_FUTURES = 65_536;

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Save Futures");

    /** Consecutive zero-reads required before the barrier declares idle. */
    private static final int QUIET_CONFIRMATIONS = 3;

    /** Gap between zero-reads, in milliseconds. */
    private static final long QUIET_INTERVAL_MS = 20L;

    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * Reserve one in-flight slot. Returns {@code false} when the bound is
     * already reached so the caller can reject rather than drop work.
     */
    public boolean tryReserve() {
        while (true) {
            int current = this.inFlight.get();
            if (current >= MAX_SAVE_FUTURES) {
                return false;
            }
            if (this.inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release() {
        this.inFlight.decrementAndGet();
    }

    /**
     * Wait until one admission slot is free. Callers that already found
     * {@link #tryReserve()} false use this so a dirty chunk is not reported
     * as saved while the cap is held by other stores. Returns false on timeout
     * or when the waiter is interrupted. The interrupt status is left set.
     */
    public boolean awaitRoom(long timeoutMs) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        while (System.nanoTime() < deadline) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (this.inFlight.get() < MAX_SAVE_FUTURES) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return false;
        }
        return this.inFlight.get() < MAX_SAVE_FUTURES;
    }

    /**
     * Bind a reserved slot to {@code stored}. The reservation is released
     * exactly once when the future completes (success or failure), including
     * the already-completed case.
     */
    public void attach(CompletableFuture<?> stored) {
        if (stored == null) {
            release();
            return;
        }
        stored.whenComplete((unused, throwable) -> release());
    }

    /**
     * Single-sample read. Diagnostic only — <b>never</b> use this as a
     * shutdown barrier; use {@link #joinFully(long)}.
     */
    public boolean isIdle() {
        return this.inFlight.get() == 0;
    }

    /**
     * Durable barrier: block until no saves are in flight and remain so for a
     * short quiet window.
     *
     * @param timeoutMs hard upper bound; on expiry the caller is told the
     *                  barrier did <em>not</em> confirm and must treat the
     *                  world as possibly-not-durable.
     * @return {@code true} when the tracker confirmed idle. {@code false} on
     *         timeout or interruption. Interruption leaves the interrupt status set.
     */
    public boolean joinFully(long timeoutMs) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        int consecutiveZero = 0;
        int lastObserved = -1;
        while (System.nanoTime() < deadline) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
            final int current = this.inFlight.get();
            if (current == 0) {
                if (++consecutiveZero >= QUIET_CONFIRMATIONS) {
                    return true;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(QUIET_INTERVAL_MS));
                continue;
            }
            consecutiveZero = 0;
            if (current != lastObserved) {
                lastObserved = current;
            }
            Thread.onSpinWait();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return false;
        }
        LOGGER.error("Save-future barrier did not confirm idle within {}ms ({} still in flight); "
                + "world data may not be durable", timeoutMs, this.inFlight.get());
        return false;
    }

    public int inFlight() {
        return this.inFlight.get();
    }
}
