package network.vonix.threadedhorizons.common.threading.scheduler;

import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class SchedulerLockWaiterBoundTest {

    @Test
    void waitingLocksConsumeAdmittedBoundAndSecondAdmissionIsRejected() throws Exception {
        SchedulerThread scheduler = newScheduler(1, 1);
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        ChunkPos pos = new ChunkPos(55, 56);
        AsyncLock.LockToken held = named.acquireLock(pos).toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            SchedulingAsyncCombinedLock<String> first = new SchedulingAsyncCombinedLock<>(
                    named,
                    Set.of(pos),
                    () -> 0,
                    scheduler,
                    () -> CompletableFuture.completedFuture("first")
            );
            assertTrue(scheduler.addPendingLock(first));
            waitForAdmittedLock(scheduler, 1);
            assertEquals(0, scheduler.getPendingLockCount(), "contended lock must leave the pending queue");

            SchedulingAsyncCombinedLock<String> second = new SchedulingAsyncCombinedLock<>(
                    named,
                    Set.of(new ChunkPos(57, 58)),
                    () -> 0,
                    scheduler,
                    () -> CompletableFuture.completedFuture("second")
            );
            assertFalse(scheduler.addPendingLock(second),
                    "admitted-lock bound must include waiters, not only pending queue entries");
            assertTrue(second.getFuture().isCompletedExceptionally());

            held.releaseLock();
            assertEquals("first", first.getFuture().get(5, TimeUnit.SECONDS));
            waitForAdmittedLock(scheduler, 0);
        } finally {
            scheduler.shutdown();
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void waitForAdmittedLock(SchedulerThread scheduler, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && admittedLockCount(scheduler) != expected) {
            Thread.sleep(10L);
        }
        assertEquals(expected, admittedLockCount(scheduler));
    }

    private static SchedulerThread newScheduler(int maxPendingLocks, int maxAdmittedLocks) throws Exception {
        Constructor<SchedulerThread> constructor = SchedulerThread.class
                .getDeclaredConstructor(int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(maxPendingLocks, maxAdmittedLocks);
    }

    private static int admittedLockCount(SchedulerThread scheduler) throws Exception {
        Method method = SchedulerThread.class.getDeclaredMethod("getAdmittedLockCount");
        method.setAccessible(true);
        return (Integer) method.invoke(scheduler);
    }
}
