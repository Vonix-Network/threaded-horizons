package network.vonix.threadedhorizons.common.threading.scheduler;

import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.threading.worldgen.ChunkStatusUtils;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class SchedulerPipelineDimensionKeyTest {

    @AfterEach
    void resetScheduler() {
        SchedulerThread.replaceInstance();
    }

    @Test
    void distinctWorldsWithSameChunkPosConsumeTwoSlotsAndIndependentLeaves() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        Object overworld = new Object();
        Object nether = new Object();
        ChunkPos origin = new ChunkPos(0, 0);

        assertTrue(scheduler.acquireChunkPipelineAsync(overworld, origin).isDone());
        assertTrue(scheduler.acquireChunkPipelineAsync(nether, origin).isDone());
        assertEquals(2, scheduler.getPipelineCount(), "each (world, pos) pair occupies its own slot");

        scheduler.leaveChunkPipeline(overworld, origin);
        assertEquals(1, scheduler.getPipelineCount(), "releasing one world must not drop the other world's slot");

        int extra = 0;
        while (scheduler.getPipelineCount() < scheduler.getPipelineLimit()) {
            scheduler.enterChunkPipeline(overworld, new ChunkPos(extra++, 1));
        }
        assertEquals(scheduler.getPipelineLimit(), scheduler.getPipelineCount());

        CompletableFuture<Void> blocked = scheduler.acquireChunkPipelineAsync(overworld, new ChunkPos(99, 99));
        assertFalse(blocked.isDone(), "pipeline limit still bounds total admissions across dimensions");
        blocked.cancel(true);

        scheduler.leaveChunkPipeline(nether, origin);
        assertEquals(scheduler.getPipelineLimit() - 1, scheduler.getPipelineCount());

        for (int index = 0; index < extra; index++) {
            scheduler.leaveChunkPipeline(overworld, new ChunkPos(index, 1));
        }
        assertEquals(0, scheduler.getPipelineCount());
        assertEquals(0, scheduler.getPipelineWaiterCount());
    }

    @Test
    void sameWorldSamePosReentryDoesNotDoubleCountAndReleasesOnOutermostLeave() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        Object overworld = new Object();
        ChunkPos origin = new ChunkPos(0, 0);

        assertTrue(scheduler.acquireChunkPipelineAsync(overworld, origin).isDone());
        assertTrue(scheduler.acquireChunkPipelineAsync(overworld, origin).isDone());
        assertEquals(1, scheduler.getPipelineCount(), "same (world, pos) reentry must not consume a second slot");

        scheduler.leaveChunkPipeline(overworld, origin);
        assertEquals(1, scheduler.getPipelineCount(), "inner leave must keep the outer slot");

        scheduler.leaveChunkPipeline(overworld, origin);
        assertEquals(0, scheduler.getPipelineCount(), "outermost leave must fully release");

        scheduler.leaveChunkPipeline(overworld, origin);
        assertEquals(0, scheduler.getPipelineCount(), "extra leave must not underflow or throw");
    }

    @Test
    void pipelineLimitBoundsMixedDimensionAdmissions() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        int limit = scheduler.getPipelineLimit();
        Object overworld = new Object();
        Object nether = new Object();
        List<Held> held = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            Object world = (index & 1) == 0 ? overworld : nether;
            ChunkPos pos = new ChunkPos(index, 0);
            scheduler.enterChunkPipeline(world, pos);
            held.add(new Held(world, pos));
        }
        assertEquals(limit, scheduler.getPipelineCount());

        CompletableFuture<Void> waiter = scheduler.acquireChunkPipelineAsync(overworld, new ChunkPos(limit + 7, 0));
        assertFalse(waiter.isDone());
        assertEquals(1, scheduler.getPipelineWaiterCount());

        Held released = held.remove(held.size() - 1);
        scheduler.leaveChunkPipeline(released.world, released.pos);
        waitUntil(() -> waiter.isDone() && !waiter.isCompletedExceptionally() && scheduler.getPipelineWaiterCount() == 0, 3);
        assertEquals(limit, scheduler.getPipelineCount());

        scheduler.leaveChunkPipeline(overworld, new ChunkPos(limit + 7, 0));
        for (Held slot : held) {
            scheduler.leaveChunkPipeline(slot.world, slot.pos);
        }
        assertEquals(0, scheduler.getPipelineCount());
    }

    @Test
    void cancellationAndShutdownReleaseWorldKeyedSlotsExactlyOnce() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        int limit = scheduler.getPipelineLimit();
        Object overworld = new Object();
        Object nether = new Object();
        List<Held> held = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            Object world = (index & 1) == 0 ? overworld : nether;
            ChunkPos pos = new ChunkPos(index, 3);
            scheduler.enterChunkPipeline(world, pos);
            held.add(new Held(world, pos));
        }

        ChunkPos cancelledPos = new ChunkPos(limit + 11, 3);
        CompletableFuture<Void> cancelled = scheduler.acquireChunkPipelineAsync(nether, cancelledPos);
        assertFalse(cancelled.isDone());
        assertTrue(cancelled.cancel(true));

        Held first = held.remove(0);
        scheduler.leaveChunkPipeline(first.world, first.pos);
        waitUntil(() -> scheduler.getPipelineWaiterCount() == 0 && scheduler.getPipelineCount() == limit - 1, 3);
        assertEquals(limit - 1, scheduler.getPipelineCount());
        assertEquals(0, scheduler.getPipelineWaiterCount());

        scheduler.enterChunkPipeline(first.world, first.pos);
        held.add(0, first);
        assertEquals(limit, scheduler.getPipelineCount());

        CompletableFuture<Void> shutdownWaiter = scheduler.acquireChunkPipelineAsync(overworld, new ChunkPos(limit + 12, 3));
        assertFalse(shutdownWaiter.isDone());
        scheduler.shutdown();
        assertTrue(shutdownWaiter.isDone());
        assertTrue(shutdownWaiter.isCompletedExceptionally());
        assertEquals(0, scheduler.getPipelineWaiterCount());

        for (Held slot : held) {
            scheduler.leaveChunkPipeline(slot.world, slot.pos);
        }
        assertEquals(0, scheduler.getPipelineCount());
    }

    @Test
    void runChunkGenWithLockOnTwoWorldsConsumesTwoSlotsForTheSamePos() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        Object overworld = new Object();
        Object nether = new Object();
        ChunkPos origin = new ChunkPos(0, 0);
        CountDownLatch hold = new CountDownLatch(1);

        CompletableFuture<String> first = ChunkStatusUtils.runChunkGenWithLock(
                origin, 0, () -> 0, AsyncNamedLock.createFair(),
                () -> CompletableFuture.supplyAsync(() -> awaitHold(hold)),
                null, null, overworld);
        CompletableFuture<String> second = ChunkStatusUtils.runChunkGenWithLock(
                origin, 0, () -> 0, AsyncNamedLock.createFair(),
                () -> CompletableFuture.supplyAsync(() -> awaitHold(hold)),
                null, null, nether);

        waitUntil(() -> scheduler.getPipelineCount() == 2, 5);
        assertEquals(2, scheduler.getPipelineCount(), "two worlds generating (0,0) must occupy two pipeline slots");
        hold.countDown();
        assertEquals("ok", first.get(5, TimeUnit.SECONDS));
        assertEquals("ok", second.get(5, TimeUnit.SECONDS));
        waitUntil(() -> scheduler.getPipelineCount() == 0 && scheduler.getPipelineWaiterCount() == 0, 3);
        assertEquals(0, scheduler.getPipelineCount());
    }

    @Test
    void generateOverwritePassesServerLevelAsPipelineWorldKey() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/worldgen/MixinChunkStatus.java"));
        assertTrue(body.contains("status, holder, world)"),
                "ChunkStatus.generate must pass ServerLevel so pipeline keys are per-world");
        assertFalse(body.contains("leaveChunkPipeline"),
                "ChunkStatusUtils already pairs acquire/leave");
    }

    private static String awaitHold(CountDownLatch hold) {
        try {
            hold.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return "ok";
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

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static final class Held {
        private final Object world;
        private final ChunkPos pos;

        private Held(Object world, ChunkPos pos) {
            this.world = world;
            this.pos = pos;
        }
    }
}
