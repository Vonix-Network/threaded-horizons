package network.vonix.threadedhorizons.common.threading.worldgen;

import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class ChunkStatusUtilsAdmissionCancellationTest {

    @AfterEach
    void resetScheduler() {
        SchedulerThread.replaceInstance();
    }

    @Test
    void cancellingQueuedRunChunkGenWithLockDoesNotRetainPipelineSlot() throws Exception {
        final SchedulerThread scheduler = SchedulerThread.INSTANCE;
        final int limit = scheduler.getPipelineLimit();
        final List<ChunkPos> held = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            ChunkPos pos = new ChunkPos(index, 51);
            scheduler.enterChunkPipeline(pos);
            held.add(pos);
        }
        assertEquals(limit, scheduler.getPipelineCount());

        final ChunkPos queuedPos = new ChunkPos(limit + 50, 51);
        final AtomicBoolean ran = new AtomicBoolean();
        CompletableFuture<String> queued = ChunkStatusUtils.runChunkGenWithLock(
                queuedPos,
                0,
                () -> 0,
                AsyncNamedLock.createFair(),
                () -> {
                    ran.set(true);
                    return CompletableFuture.completedFuture("queued");
                }
        );
        assertFalse(queued.isDone());
        assertEquals(1, scheduler.getPipelineWaiterCount());

        assertTrue(queued.cancel(true));
        waitUntil(() -> scheduler.getPipelineWaiterCount() == 0, 3);

        scheduler.leaveChunkPipeline(held.remove(held.size() - 1));
        waitUntil(() -> scheduler.getPipelineCount() == limit - 1 && scheduler.getPipelineWaiterCount() == 0, 3);
        assertEquals(limit - 1, scheduler.getPipelineCount());
        assertEquals(0, scheduler.getPipelineWaiterCount());
        assertFalse(ran.get(), "cancelled queued generation must not run");

        for (ChunkPos pos : held) {
            scheduler.leaveChunkPipeline(pos);
        }
        waitUntil(() -> scheduler.getPipelineCount() == 0 && scheduler.getPipelineWaiterCount() == 0, 5);

        CompletableFuture<Void> later = scheduler.acquireChunkPipelineAsync(queuedPos);
        assertTrue(later.isDone());
        assertFalse(later.isCompletedExceptionally());
        assertEquals(1, scheduler.getPipelineCount());
        scheduler.leaveChunkPipeline(queuedPos);
        waitUntil(() -> scheduler.getPipelineCount() == 0 && scheduler.getPipelineWaiterCount() == 0, 3);
        assertEquals(0, scheduler.getPipelineCount());
        assertEquals(0, scheduler.getPipelineWaiterCount());
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
}
