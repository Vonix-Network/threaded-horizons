package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gates for the durable shutdown barrier.
 *
 * <p>These lock in the fix for the restart data-loss defect: region writes
 * buffered behind {@code RegionFileStorage.sync == false} drained during
 * process exit instead of before {@code Stopping server} completed, and any
 * hard stop in that window lost block-entity NBT (empty chests, intact
 * playerdata).
 *
 * <p>The original barrier was a single {@code isIdle()} read. A single read can
 * observe a momentary gap between a completing store and the follow-on store
 * that completion schedules, so it reported "durable" while work was still
 * outstanding. {@link SaveFutureTracker#joinFully(long)} must not.
 */
@Timeout(60)
class SaveFutureBarrierDurabilityTest {

    /**
     * The defect that caused the data loss: a store that schedules another
     * store when it completes. A single-sample {@code isIdle()} read can catch
     * the gap; the quiet-window barrier must not.
     */
    @Test
    void joinFullyDoesNotReturnIdleAcrossCompletionScheduledFollowOnWork() throws Exception {
        final SaveFutureTracker tracker = new SaveFutureTracker();
        final AtomicInteger storesRun = new AtomicInteger();
        final int chainLength = 5;

        // Simulate: store N completes, and its completion callback enqueues
        // store N+1. This is the shape TH's save path has, because
        // DirtyChunkGenerations.applyStoreOutcome runs from whenComplete and a
        // newer mutation can re-mark the chunk dirty, scheduling another store.
        Runnable[] schedule = new Runnable[1];
        schedule[0] = () -> {
            if (storesRun.incrementAndGet() > chainLength) {
                return;
            }
            assertTrue(tracker.tryReserve());
            CompletableFuture<Void> stored = new CompletableFuture<>();
            tracker.attach(stored);
            // Complete on another thread, and re-enter schedule from the
            // completion, i.e. exactly the window a single read can miss.
            Thread followOn = new Thread(() -> {
                stored.complete(null);
                schedule[0].run();
            }, "th-follow-on-store");
            followOn.setDaemon(true);
            followOn.start();
        };
        schedule[0].run();

        boolean confirmed = tracker.joinFully(TimeUnit.SECONDS.toMillis(30));

        assertTrue(confirmed, "barrier must confirm idle once the whole store chain drains");
        assertTrue(storesRun.get() > chainLength,
                "chain must have actually run to completion (ran " + storesRun.get() + ")");
        assertTrue(tracker.isIdle(), "no store may remain in flight after a confirmed barrier");
    }

    /**
     * A barrier that cannot confirm must report failure rather than claim
     * success. Reporting success with work outstanding is the class of bug that
     * lost player data.
     */
    @Test
    void joinFullyReportsFailureOnTimeoutInsteadOfClaimingDurability() {
        final SaveFutureTracker tracker = new SaveFutureTracker();
        assertTrue(tracker.tryReserve());
        CompletableFuture<Void> neverCompletes = new CompletableFuture<>();
        tracker.attach(neverCompletes);

        boolean confirmed = tracker.joinFully(150L);

        assertFalse(confirmed, "a barrier that cannot drain must not report durability");
        assertFalse(tracker.isIdle(), "the outstanding store is still in flight");

        // Cleanup so the test does not leak an in-flight reservation.
        neverCompletes.complete(null);
        assertTrue(tracker.isIdle());
    }

    /** An already-idle tracker must confirm promptly, not spin for the full timeout. */
    @Test
    void joinFullyReturnsPromptlyWhenAlreadyIdle() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        long started = System.nanoTime();
        assertTrue(tracker.joinFully(TimeUnit.SECONDS.toMillis(30)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMs < TimeUnit.SECONDS.toMillis(10),
                "idle barrier must not burn the full timeout (took " + elapsedMs + "ms)");
    }

    /**
     * A new save arriving during the quiet window must reset confirmation.
     * This is the "looks idle for a moment" case that the old single read
     * accepted.
     */
    @Test
    void joinFullyResetsConfirmationWhenNewWorkArrives() throws Exception {
        final SaveFutureTracker tracker = new SaveFutureTracker();
        final CountDownLatch injected = new CountDownLatch(1);

        Thread injector = new Thread(() -> {
            try {
                // Land a new store right after the barrier starts seeing zeros.
                Thread.sleep(25L);
                assertTrue(tracker.tryReserve());
                CompletableFuture<Void> late = new CompletableFuture<>();
                tracker.attach(late);
                injected.countDown();
                Thread.sleep(60L);
                late.complete(null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "th-late-store");
        injector.setDaemon(true);
        injector.start();

        boolean confirmed = tracker.joinFully(TimeUnit.SECONDS.toMillis(30));
        assertTrue(injected.await(5, TimeUnit.SECONDS), "injector must have run");
        assertTrue(confirmed, "barrier must eventually confirm after the late store drained");
        assertTrue(tracker.isIdle());
    }
}
