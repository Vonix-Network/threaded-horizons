package network.vonix.threadedhorizons.common.threading;

import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulingAsyncCombinedLock;
import network.vonix.threadedhorizons.common.threading.worldgen.ChunkStatusUtils;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class LockAndSchedulerTest {

    @AfterEach
    void resetScheduler() {
        SchedulerThread.replaceInstance();
    }

    @Test
    void singleThreadedHoldsUntilStageCompletes() throws Exception {
        AsyncLock lock = AsyncLock.createFair();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean overlap = new AtomicBoolean();
        AtomicInteger inside = new AtomicInteger();
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> first =
                ChunkStatusUtils.ChunkStatusThreadingType.SINGLE_THREADED.runTask(lock, () -> {
                    inside.incrementAndGet();
                    entered.countDown();
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        inside.decrementAndGet();
                        return Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED);
                    });
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> second =
                ChunkStatusUtils.ChunkStatusThreadingType.SINGLE_THREADED.runTask(lock, () -> {
                    if (inside.get() > 0) {
                        overlap.set(true);
                    }
                    return CompletableFuture.completedFuture(Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED));
                });
        Thread.sleep(100);
        assertFalse(second.isDone());
        release.countDown();
        first.join();
        second.join();
        assertFalse(overlap.get());
    }

    @Test
    void combinedLockReleasesOnSynchronousThrow() {
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        SchedulingAsyncCombinedLock<String> lock = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(0, 0)),
                () -> 0,
                SchedulerThread.INSTANCE,
                () -> {
                    throw new IllegalStateException("supplier failed");
                }
        );
        assertTrue(lock.tryAcquire());
        lock.doAction(() -> {});
        assertTrue(lock.getFuture().isCompletedExceptionally());
        SchedulingAsyncCombinedLock<String> second = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(0, 0)),
                () -> 0,
                SchedulerThread.INSTANCE,
                () -> CompletableFuture.completedFuture("ok")
        );
        assertTrue(second.tryAcquire());
        second.doAction(() -> {});
        assertEquals("ok", second.getFuture().join());
    }

    @Test
    void throwingCancelCheckReleasesTokenAndSchedulerPermit() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        int permits = scheduler.getAvailablePermits();
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        AtomicInteger checks = new AtomicInteger();
        SchedulingAsyncCombinedLock<String> lock = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(9, 9)),
                () -> 0,
                scheduler,
                () -> CompletableFuture.completedFuture("ok"),
                () -> {
                    if (checks.incrementAndGet() > 2) {
                        throw new IllegalStateException("cancel check");
                    }
                    return false;
                }
        );
        assertTrue(lock.tryAcquire());
        lock.doAction(() -> {});
        assertTrue(lock.getFuture().isCompletedExceptionally());

        SchedulingAsyncCombinedLock<String> second = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(9, 9)),
                () -> 0,
                scheduler,
                () -> CompletableFuture.completedFuture("reacquired")
        );
        assertTrue(second.tryAcquire());
        second.doAction(() -> {});
        assertEquals("reacquired", second.getFuture().join());

        AtomicInteger queuedChecks = new AtomicInteger();
        SchedulingAsyncCombinedLock<String> queued = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(10, 10)),
                () -> 0,
                scheduler,
                () -> CompletableFuture.completedFuture("queued"),
                () -> {
                    if (queuedChecks.incrementAndGet() > 2) {
                        throw new IllegalStateException("cancel check");
                    }
                    return false;
                }
        );
        assertTrue(scheduler.addPendingLock(queued));
        assertTrue(queued.getFuture().isCompletedExceptionally()
                || queued.getFuture().exceptionally(ignored -> "failed").get(5, TimeUnit.SECONDS).equals("failed"));
        assertTrue(queued.getFuture().isCompletedExceptionally());
        assertEquals(permits, scheduler.getAvailablePermits());
        assertTrue(scheduler.isAlive());
    }

    @Test
    void cancelSupplierThrowDuringRequeueAfterWaitCompletesAndReleasesNamedLock() throws Exception {
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        ChunkPos pos = new ChunkPos(3, 3);
        CompletableFuture<String> firstStage = new CompletableFuture<>();
        SchedulingAsyncCombinedLock<String> first = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(pos),
                () -> 0,
                SchedulerThread.INSTANCE,
                () -> firstStage
        );
        assertTrue(first.tryAcquire());
        first.doAction(() -> {});

        AtomicBoolean throwOnCancel = new AtomicBoolean();
        IllegalStateException cancelFailure = new IllegalStateException("cancel check after wait");
        SchedulingAsyncCombinedLock<String> second = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(pos),
                () -> 0,
                SchedulerThread.INSTANCE,
                () -> CompletableFuture.completedFuture("second"),
                () -> {
                    if (throwOnCancel.get()) {
                        throw cancelFailure;
                    }
                    return false;
                }
        );
        assertFalse(second.tryAcquire());
        assertFalse(second.getFuture().isDone());

        throwOnCancel.set(true);
        firstStage.complete("first");
        assertEquals("first", first.getFuture().get(5, TimeUnit.SECONDS));

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> second.getFuture().get(5, TimeUnit.SECONDS));
        assertEquals(cancelFailure, thrown.getCause());

        SchedulingAsyncCombinedLock<String> third = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(pos),
                () -> 0,
                SchedulerThread.INSTANCE,
                () -> CompletableFuture.completedFuture("ok")
        );
        assertTrue(third.tryAcquire());
        third.doAction(() -> {});
        assertEquals("ok", third.getFuture().join());
    }

    @Test
    void schedulerRejectsWorkAfterShutdown() {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        scheduler.shutdown();
        assertThrows(IllegalStateException.class, () -> scheduler.execute(() -> {}));
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        SchedulingAsyncCombinedLock<String> lock = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(1, 1)),
                () -> 0,
                scheduler,
                () -> CompletableFuture.completedFuture("late")
        );
        scheduler.addPendingLock(lock);
        assertTrue(lock.getFuture().isCompletedExceptionally());
    }
}
