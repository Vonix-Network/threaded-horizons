package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class SaveFutureTrackerTest {

    @Test
    void reserveIsFailClosedAtTheBound() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        int reserved = 0;
        while (tracker.tryReserve()) {
            reserved++;
            if (reserved > SaveFutureTracker.MAX_SAVE_FUTURES) {
                break;
            }
        }
        assertEquals(SaveFutureTracker.MAX_SAVE_FUTURES, reserved);
        assertFalse(tracker.tryReserve(), "admission must reject rather than drop the 65537th save");
        assertEquals(SaveFutureTracker.MAX_SAVE_FUTURES, tracker.inFlight());
        tracker.release();
        assertTrue(tracker.tryReserve());
        assertEquals(SaveFutureTracker.MAX_SAVE_FUTURES, tracker.inFlight());
    }

    @Test
    void attachReleasesOnSuccessFailureAndAlreadyCompleted() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        assertTrue(tracker.tryReserve());
        tracker.attach(CompletableFuture.completedFuture(null));
        assertTrue(tracker.isIdle());

        assertTrue(tracker.tryReserve());
        CompletableFuture<Void> failed = new CompletableFuture<>();
        tracker.attach(failed);
        assertEquals(1, tracker.inFlight());
        failed.completeExceptionally(new RuntimeException("store failed"));
        assertTrue(tracker.isIdle());

        assertTrue(tracker.tryReserve());
        tracker.attach(null);
        assertTrue(tracker.isIdle());
    }

    @Test
    void awaitIdleWaitsForInFlightStoresWithoutAllOf() throws Exception {
        SaveFutureTracker tracker = new SaveFutureTracker();
        CompletableFuture<Void> stored = new CompletableFuture<>();
        assertTrue(tracker.tryReserve());
        tracker.attach(stored);
        AtomicBoolean observedBusy = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            started.countDown();
            while (!tracker.isIdle()) {
                observedBusy.set(true);
                Thread.onSpinWait();
            }
        }, "save-tracker-idle");
        waiter.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(20L);
        assertFalse(tracker.isIdle());
        stored.complete(null);
        waiter.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(waiter.isAlive());
        assertTrue(tracker.isIdle());
        assertTrue(observedBusy.get());
    }

    @Test
    void joinDoesNotClearUnsavedBeforeStoreCompletes() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        long generation = DirtyChunkGenerations.markMutated(chunk);
        CompletableFuture<Void> stored = new CompletableFuture<>();
        assertTrue(tracker.tryReserve());
        tracker.attach(stored);
        stored.whenComplete((unused, throwable) ->
                DirtyChunkGenerations.applyStoreOutcome(chunk, generation, throwable == null, unsaved));

        assertFalse(tracker.isIdle());
        assertTrue(unsaved.get(), "unsaved must stay set until the store future completes");
        assertEquals(generation, DirtyChunkGenerations.current(chunk));
        stored.complete(null);
        assertTrue(tracker.isIdle());
        assertFalse(unsaved.get(), "durable success of the current generation may clear unsaved");
        assertEquals(generation, DirtyChunkGenerations.current(chunk),
                "durable success must not recycle the generation");
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void overlappingMutationSurvivesJoinOfOlderStore() throws Exception {
        SaveFutureTracker tracker = new SaveFutureTracker();
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        long first = DirtyChunkGenerations.markMutated(chunk);
        CompletableFuture<Void> stored = new CompletableFuture<>();
        assertTrue(tracker.tryReserve());
        tracker.attach(stored);
        stored.whenComplete((unused, throwable) ->
                DirtyChunkGenerations.applyStoreOutcome(chunk, first, throwable == null, unsaved));

        long newer = DirtyChunkGenerations.markMutated(chunk);
        unsaved.set(true);
        stored.complete(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !tracker.isIdle()) {
            Thread.onSpinWait();
        }
        assertTrue(tracker.isIdle());
        assertTrue(unsaved.get(), "newer mutation must remain dirty after older store succeeds");
        assertEquals(newer, DirtyChunkGenerations.current(chunk));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void failedReserveDoesNotTrackADroppedSave() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        List<CompletableFuture<Void>> held = new ArrayList<>();
        for (int i = 0; i < SaveFutureTracker.MAX_SAVE_FUTURES; i++) {
            assertTrue(tracker.tryReserve());
            CompletableFuture<Void> future = new CompletableFuture<>();
            tracker.attach(future);
            held.add(future);
        }
        assertFalse(tracker.tryReserve());
        for (CompletableFuture<Void> future : held) {
            future.complete(null);
        }
        assertTrue(tracker.isIdle());
    }

    @Test
    void oneCompletionReleasesOnceEvenIfAnEarlierCallbackThrows() {
        SaveFutureTracker tracker = new SaveFutureTracker();
        assertTrue(tracker.tryReserve());
        CompletableFuture<Void> stored = new CompletableFuture<>();
        stored.whenComplete((unused, throwable) -> {
            throw new IllegalStateException("sibling callback");
        });
        tracker.attach(stored);
        stored.complete(null);
        stored.complete(null);
        assertEquals(0, tracker.inFlight(), "one reservation is released once");
    }

    @Test
    void awaitRoomReturnsFalseWhenTheWaiterIsInterrupted() throws Exception {
        SaveFutureTracker tracker = new SaveFutureTracker();
        for (int index = 0; index < SaveFutureTracker.MAX_SAVE_FUTURES; index++) {
            assertTrue(tracker.tryReserve());
        }
        boolean[] room = {true};
        boolean[] interrupted = {false};
        Thread waiter = new Thread(() -> {
            room[0] = tracker.awaitRoom(30_000L);
            interrupted[0] = Thread.currentThread().isInterrupted();
        }, "await-room-interrupt");
        waiter.start();
        Thread.sleep(30L);
        waiter.interrupt();
        waiter.join(2_000L);
        assertFalse(waiter.isAlive(), "awaitRoom must return after interruption");
        assertFalse(room[0]);
        assertTrue(interrupted[0], "the interrupt status must stay set");
    }

    @Test
    void joinFullyReturnsFalseWhenTheWaiterIsInterrupted() throws Exception {
        SaveFutureTracker tracker = new SaveFutureTracker();
        assertTrue(tracker.tryReserve());
        CompletableFuture<Void> blocked = new CompletableFuture<>();
        tracker.attach(blocked);
        boolean[] drained = {true};
        boolean[] interrupted = {false};
        Thread waiter = new Thread(() -> {
            drained[0] = tracker.joinFully(30_000L);
            interrupted[0] = Thread.currentThread().isInterrupted();
        }, "join-fully-interrupt");
        waiter.start();
        Thread.sleep(30L);
        waiter.interrupt();
        waiter.join(2_000L);
        assertFalse(waiter.isAlive(), "joinFully must return after interruption");
        assertFalse(drained[0]);
        assertTrue(interrupted[0], "the interrupt status must stay set");
        blocked.complete(null);
        assertTrue(tracker.isIdle());
    }
}
