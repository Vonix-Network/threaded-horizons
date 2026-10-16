package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure contract for the tracker plus {@link SaveBarrier}. The production
 * mixin calls these helpers; this class does not execute that mixin.
 * {@code SavePathBytecodeTest} checks the compiled call order.
 */
@Timeout(30)
class FailedFlushBarrierTest {

    private final FailedChunkSaves failedSaves = new FailedChunkSaves();

    @Test
    void persistentFailedWriteIsNotACompletedFlush() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        try {
            long generation = DirtyChunkGenerations.markMutated(chunk);
            completeStore(tracker, chunk, generation, unsaved, new IllegalStateException("disk"));
            ChunkSaveBarrierException failure = assertThrows(ChunkSaveBarrierException.class, () ->
                    SaveBarrier.awaitQuietAndRetry(tracker, 2_000L, failedSaves::hasPending, () -> false));
            assertTrue(failure.getMessage().contains("did not drain"), failure.getMessage());
            assertTrue(unsaved.get(), "a failed flush must leave the chunk dirty");
            assertTrue(failedSaves.hasPending());
            assertEquals(0, tracker.inFlight());
            assertEquals(generation, DirtyChunkGenerations.current(chunk));
        } finally {
            DirtyChunkGenerations.forget(chunk);
        }
    }

    @Test
    void barrierReturnsOnlyAfterALaterStoreIsDurable() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        try {
            long failedGeneration = DirtyChunkGenerations.markMutated(chunk);
            completeStore(tracker, chunk, failedGeneration, unsaved, new IllegalStateException("disk"));
            assertTrue(failedSaves.hasPending());
            SaveBarrier.awaitQuietAndRetry(tracker, 5_000L, failedSaves::hasPending, () -> {
                long generation = DirtyChunkGenerations.captureForSave(chunk);
                completeStore(tracker, chunk, generation, unsaved, null);
                return true;
            });
            assertFalse(unsaved.get(), "the retry's durable completion clears unsaved");
            assertFalse(failedSaves.hasPending());
            assertTrue(tracker.isIdle());
            assertEquals(failedGeneration, DirtyChunkGenerations.current(chunk));
        } finally {
            DirtyChunkGenerations.forget(chunk);
        }
    }

    @Test
    void failureIsRecordedBeforeTheReservationIsReleased() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        Object chunk = new Object();
        AtomicInteger inFlightDuringOutcome = new AtomicInteger(-1);
        assertTrue(tracker.tryReserve());
        CompletableFuture<Void> stored = new CompletableFuture<>();
        bindOutcomeThenRelease(tracker, stored, (throwable) -> {
            inFlightDuringOutcome.set(tracker.inFlight());
            if (throwable != null) {
                failedSaves.record(chunk);
            }
        });
        stored.completeExceptionally(new IllegalStateException("disk"));
        assertEquals(1, inFlightDuringOutcome.get(),
                "the outcome callback must observe the reservation still held");
        assertEquals(0, tracker.inFlight(), "attach releases that reservation once");
        assertTrue(failedSaves.hasPending());

        SaveFutureTracker alreadyDone = new SaveFutureTracker();
        Object second = new Object();
        AtomicInteger duringInline = new AtomicInteger(-1);
        assertTrue(alreadyDone.tryReserve());
        CompletableFuture<Void> failed = CompletableFuture.failedFuture(new IllegalStateException("disk"));
        bindOutcomeThenRelease(alreadyDone, failed, (throwable) -> {
            duringInline.set(alreadyDone.inFlight());
            if (throwable != null) {
                failedSaves.record(second);
            }
        });
        assertEquals(1, duringInline.get(), "an already-failed future must record before release");
        assertEquals(0, alreadyDone.inFlight());
        assertTrue(failedSaves.hasPending());
    }

    @Test
    void barrierNoticesInterruptionWhileAStoreIsOutstanding() throws Exception {
        SaveFutureTracker tracker = new SaveFutureTracker();
        assertTrue(tracker.tryReserve());
        CompletableFuture<Void> blocked = new CompletableFuture<>();
        tracker.attach(blocked);
        failedSaves.record(new Object());
        Throwable[] thrown = {null};
        Thread waiter = new Thread(() -> {
            try {
                SaveBarrier.awaitQuietAndRetry(tracker, 30_000L, failedSaves::hasPending, () -> false);
            } catch (Throwable throwable) {
                thrown[0] = throwable;
            }
        }, "barrier-interrupt");
        waiter.start();
        Thread.sleep(30L);
        waiter.interrupt();
        waiter.join(2_000L);
        assertFalse(waiter.isAlive(), "the barrier must leave the wait when interrupted");
        assertTrue(thrown[0] instanceof ChunkSaveBarrierException, String.valueOf(thrown[0]));
        assertTrue(thrown[0].getMessage().contains("interrupted"), thrown[0].getMessage());
        blocked.complete(null);
    }

    @Test
    void admissionEndIsIdempotentAndReleasesOnce() {
        Object chunk = new Object();
        assertTrue(ChunkSaveAdmission.begin(chunk, 4L));
        assertFalse(ChunkSaveAdmission.begin(chunk, 4L));
        ChunkSaveAdmission.end(chunk, 4L);
        ChunkSaveAdmission.end(chunk, 4L);
        assertFalse(ChunkSaveAdmission.isAdmitted(chunk, 4L));
        assertTrue(ChunkSaveAdmission.begin(chunk, 4L));
        ChunkSaveAdmission.end(chunk, 4L);
        assertFalse(ChunkSaveAdmission.isAdmitted(chunk, 4L));
    }

    /**
     * Same callback order as {@code MixinThreadedAnvilChunkStorage#save}:
     * record or clear, then the tracker release registered by {@code attach}.
     */
    private void completeStore(
            SaveFutureTracker tracker,
            Object chunk,
            long generation,
            AtomicBoolean unsaved,
            Throwable failure
    ) {
        CompletableFuture<Void> stored = new CompletableFuture<>();
        assertTrue(tracker.tryReserve());
        bindOutcomeThenRelease(tracker, stored, (throwable) -> {
            if (throwable != null) {
                failedSaves.record(chunk);
            }
            DirtyChunkGenerations.applyStoreOutcome(chunk, generation, throwable == null, unsaved);
            if (throwable == null && !unsaved.get()) {
                failedSaves.clear(chunk);
            }
        });
        if (failure == null) {
            stored.complete(null);
        } else {
            stored.completeExceptionally(failure);
        }
        assertEquals(0, tracker.inFlight());
    }

    /**
     * Production shape: outcome completes a gate, and {@code attach} releases
     * only when that gate completes. Sibling callbacks on the store future are
     * last-registered-first, so the release must not be one of them.
     */
    private static void bindOutcomeThenRelease(
            SaveFutureTracker tracker,
            CompletableFuture<Void> stored,
            java.util.function.Consumer<Throwable> outcome
    ) {
        CompletableFuture<Void> releaseGate = new CompletableFuture<>();
        stored.whenComplete((unused, throwable) -> {
            try {
                outcome.accept(throwable);
            } finally {
                if (throwable != null) {
                    releaseGate.completeExceptionally(throwable);
                } else {
                    releaseGate.complete(null);
                }
            }
        });
        tracker.attach(releaseGate);
    }
}
