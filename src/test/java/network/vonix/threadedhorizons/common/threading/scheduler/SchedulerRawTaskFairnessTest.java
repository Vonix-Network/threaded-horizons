package network.vonix.threadedhorizons.common.threading.scheduler;

import com.ibm.asyncutil.locks.AsyncNamedLock;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class SchedulerRawTaskFairnessTest {

    @AfterEach
    void resetScheduler() {
        SchedulerThread.replaceInstance();
    }

    @Test
    void rawTaskRunsDespiteSelfReenqueuingCompletedLockChain() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        AtomicBoolean stopChain = new AtomicBoolean(false);
        AtomicInteger lockProgress = new AtomicInteger();
        CountDownLatch rawRan = new CountDownLatch(1);
        AtomicInteger nextChunk = new AtomicInteger();

        Supplier<CompletableFuture<String>> supplier = new Supplier<>() {
            @Override
            public CompletableFuture<String> get() {
                int step = lockProgress.incrementAndGet();
                if (!stopChain.get()) {
                    int chunk = nextChunk.incrementAndGet();
                    SchedulingAsyncCombinedLock<String> next = new SchedulingAsyncCombinedLock<>(
                            named,
                            Set.of(new ChunkPos(chunk, 70)),
                            () -> 0,
                            scheduler,
                            this
                    );
                    scheduler.addPendingLock(next);
                }
                return CompletableFuture.completedFuture("lock-" + step);
            }
        };

        SchedulingAsyncCombinedLock<String> first = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(0, 70)),
                () -> 0,
                scheduler,
                supplier
        );
        assertTrue(scheduler.addPendingLock(first));
        scheduler.execute(rawRan::countDown);

        assertTrue(rawRan.await(5, TimeUnit.SECONDS),
                "raw task must run before unbounded lock chain is stopped; lockProgress=" + lockProgress.get());
        stopChain.set(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && scheduler.getPendingLockCount() > 0) {
            Thread.sleep(10L);
        }
        assertTrue(lockProgress.get() >= 1);
    }

    @Test
    void sustainedHighPriorityLocksStillAllowBoundedRawProgress() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        AtomicBoolean run = new AtomicBoolean(true);
        AtomicInteger lockDone = new AtomicInteger();
        AtomicInteger rawDone = new AtomicInteger();
        AtomicInteger nextChunk = new AtomicInteger();

        Supplier<CompletableFuture<String>> supplier = new Supplier<>() {
            @Override
            public CompletableFuture<String> get() {
                lockDone.incrementAndGet();
                if (run.get()) {
                    int chunk = nextChunk.incrementAndGet();
                    SchedulingAsyncCombinedLock<String> next = new SchedulingAsyncCombinedLock<>(
                            named,
                            Set.of(new ChunkPos(chunk, 71)),
                            () -> -1,
                            scheduler,
                            this
                    );
                    scheduler.addPendingLock(next);
                }
                return CompletableFuture.completedFuture("ok");
            }
        };

        for (int seed = 0; seed < 4; seed++) {
            int chunk = nextChunk.incrementAndGet();
            scheduler.addPendingLock(new SchedulingAsyncCombinedLock<>(
                    named,
                    Set.of(new ChunkPos(chunk, 71)),
                    () -> -1,
                    scheduler,
                    supplier
            ));
        }

        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) {
            scheduler.execute(rawDone::incrementAndGet);
            Thread.sleep(5L);
        }
        run.set(false);
        long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < drainDeadline && scheduler.getPendingLockCount() > 0) {
            Thread.sleep(10L);
        }
        assertTrue(rawDone.get() >= 5, "raw tasks must make bounded progress; raw=" + rawDone.get());
        assertTrue(lockDone.get() >= 5, "lock chain must continue; locks=" + lockDone.get());
    }
}
