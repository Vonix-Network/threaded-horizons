package network.vonix.threadedhorizons.common.chunkio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-flight save tracking without a retained future queue.
 * Admission is fail-closed. Completions decrement themselves, so the
 * server tick does not walk outstanding futures.
 */
public final class SaveFutureTracker {

    public static final int MAX_SAVE_FUTURES = 65_536;

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

    public boolean isIdle() {
        return this.inFlight.get() == 0;
    }

    public int inFlight() {
        return this.inFlight.get();
    }
}
