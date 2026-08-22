package network.vonix.threadedhorizons.common.threading.scheduler;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class SchedulerPipelineCancellationTest {

    @AfterEach
    void resetScheduler() {
        SchedulerThread.replaceInstance();
    }

    @Test
    void cancelledWaiterDoesNotConsumeCapacityWhenSlotFrees() throws Exception {
        final SchedulerThread scheduler = SchedulerThread.INSTANCE;
        final int limit = scheduler.getPipelineLimit();
        final List<ChunkPos> held = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            ChunkPos pos = new ChunkPos(index, 21);
            scheduler.enterChunkPipeline(pos);
            held.add(pos);
        }
        assertEquals(limit, scheduler.getPipelineCount());

        final ChunkPos cancelledPos = new ChunkPos(limit + 50, 21);
        CompletableFuture<Void> cancelled = scheduler.acquireChunkPipelineAsync(cancelledPos);
        assertFalse(cancelled.isDone());
        assertEquals(1, scheduler.getPipelineWaiterCount());
        assertTrue(cancelled.cancel(true));

        scheduler.leaveChunkPipeline(held.remove(held.size() - 1));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline
                && (scheduler.getPipelineWaiterCount() != 0
                || scheduler.getPipelineCount() != limit - 1
                || containsPipelineKey(scheduler, cancelledPos))) {
            Thread.sleep(10L);
        }
        assertEquals(0, scheduler.getPipelineWaiterCount());
        assertFalse(containsPipelineKey(scheduler, cancelledPos), "cancelled key must not remain admitted");
        assertEquals(limit - 1, scheduler.getPipelineCount(), "capacity must return after cancel+release");

        for (ChunkPos pos : held) {
            scheduler.leaveChunkPipeline(pos);
        }
        assertEquals(0, scheduler.getPipelineCount());
    }

    @Test
    void cancelBeforeAndAfterAdmissionBothReleaseOwnedSlots() throws Exception {
        assertQueuedCancelReleasesWithoutConsumingSlot();
        assertCancelRacesAdmissionAndOwnedSlotsDrain();
    }

    /** Ordering A: cancel while still queued, then free a slot. */
    private static void assertQueuedCancelReleasesWithoutConsumingSlot() throws Exception {
        final SchedulerThread scheduler = SchedulerThread.INSTANCE;
        final int limit = scheduler.getPipelineLimit();
        final List<ChunkPos> held = fillPipeline(scheduler, limit, 31);
        CompletableFuture<Void> queued = scheduler.acquireChunkPipelineAsync(new ChunkPos(900, 31));
        assertFalse(queued.isDone());
        assertTrue(queued.cancel(true));
        scheduler.leaveChunkPipeline(held.remove(0));
        final SchedulerThread queuedScheduler = scheduler;
        final int queuedLimit = limit;
        waitUntil(() -> queuedScheduler.getPipelineWaiterCount() == 0 && queuedScheduler.getPipelineCount() == queuedLimit - 1, 3);
        assertEquals(limit - 1, scheduler.getPipelineCount());
        for (ChunkPos pos : held) {
            scheduler.leaveChunkPipeline(pos);
        }
        waitUntil(() -> queuedScheduler.getPipelineCount() == 0, 3);
    }

    /** Ordering B: race cancel against admission with barriers; owned slots must drain. */
    private static void assertCancelRacesAdmissionAndOwnedSlotsDrain() throws Exception {
        final SchedulerThread scheduler = SchedulerThread.INSTANCE;
        final List<ChunkPos> held = fillPipeline(scheduler, scheduler.getPipelineLimit(), 32);
        final ChunkPos raced = new ChunkPos(901, 32);
        final CountDownLatch queuedLatch = new CountDownLatch(1);
        final AtomicReference<CompletableFuture<Void>> futureRef = new AtomicReference<>();
        Thread admitter = new Thread(() -> {
            CompletableFuture<Void> future = scheduler.acquireChunkPipelineAsync(raced);
            futureRef.set(future);
            queuedLatch.countDown();
        }, "pipeline-admit-race");
        admitter.start();
        assertTrue(queuedLatch.await(3, TimeUnit.SECONDS));
        final CompletableFuture<Void> racedFuture = futureRef.get();
        assertTrue(racedFuture != null);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        Thread canceller = new Thread(() -> {
            awaitBarrier(barrier);
            racedFuture.cancel(true);
        }, "pipeline-cancel-race");
        Thread releaser = new Thread(() -> {
            awaitBarrier(barrier);
            scheduler.leaveChunkPipeline(held.remove(0));
        }, "pipeline-release-race");
        canceller.start();
        releaser.start();
        canceller.join(5000L);
        releaser.join(5000L);
        admitter.join(5000L);
        if (racedFuture.isDone() && !racedFuture.isCompletedExceptionally() && !racedFuture.isCancelled()) {
            scheduler.leaveChunkPipeline(raced);
        }
        for (ChunkPos pos : held) {
            scheduler.leaveChunkPipeline(pos);
        }
        final SchedulerThread racedScheduler = scheduler;
        waitUntil(() -> racedScheduler.getPipelineCount() == 0 && racedScheduler.getPipelineWaiterCount() == 0, 5);
        assertEquals(0, scheduler.getPipelineCount());
        assertEquals(0, scheduler.getPipelineWaiterCount());
    }

    @Test
    void cancellingPipelineLimitWaitersStillAllowsLaterAdmission() throws Exception {
        final SchedulerThread scheduler = SchedulerThread.INSTANCE;
        final int limit = scheduler.getPipelineLimit();
        final List<ChunkPos> held = fillPipeline(scheduler, limit, 41);
        List<CompletableFuture<Void>> cancelled = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            CompletableFuture<Void> waiter = scheduler.acquireChunkPipelineAsync(new ChunkPos(1000 + index, 41));
            assertFalse(waiter.isDone());
            assertTrue(waiter.cancel(true));
            cancelled.add(waiter);
        }
        // Cancellation removes queued waiters immediately so they cannot consume a later slot.
        waitUntil(() -> scheduler.getPipelineWaiterCount() == 0, 3);
        assertEquals(0, scheduler.getPipelineWaiterCount());
        for (CompletableFuture<Void> waiter : cancelled) {
            assertTrue(waiter.isCancelled());
        }

        // Free the whole pipeline so capacity reopens for a later non-cancelled admission.
        for (ChunkPos pos : held) {
            scheduler.leaveChunkPipeline(pos);
        }
        waitUntil(() -> scheduler.getPipelineCount() == 0, 5);

        ChunkPos later = new ChunkPos(2000, 41);
        CompletableFuture<Void> admitted = scheduler.acquireChunkPipelineAsync(later);
        assertTrue(admitted.isDone());
        assertFalse(admitted.isCompletedExceptionally());
        assertEquals(1, scheduler.getPipelineCount());
        scheduler.leaveChunkPipeline(later);
        assertEquals(0, scheduler.getPipelineCount());
    }

    private static List<ChunkPos> fillPipeline(SchedulerThread scheduler, int limit, int z) throws InterruptedException {
        List<ChunkPos> held = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            ChunkPos pos = new ChunkPos(index, z);
            scheduler.enterChunkPipeline(pos);
            held.add(pos);
        }
        return held;
    }

    private static boolean containsPipelineKey(SchedulerThread scheduler, ChunkPos pos) {
        // Pipeline count alone is insufficient; try acquiring the same key (idempotent when present).
        int before = scheduler.getPipelineCount();
        CompletableFuture<Void> probe = scheduler.acquireChunkPipelineAsync(pos);
        if (!probe.isDone()) {
            probe.cancel(true);
            return false;
        }
        // If key was already present, count unchanged; if newly added, count increased.
        int after = scheduler.getPipelineCount();
        if (after > before) {
            scheduler.leaveChunkPipeline(pos);
            return false;
        }
        return true;
    }

    private static void waitUntil(Check check, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(check.ok(), "condition not met in " + seconds + "s");
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
