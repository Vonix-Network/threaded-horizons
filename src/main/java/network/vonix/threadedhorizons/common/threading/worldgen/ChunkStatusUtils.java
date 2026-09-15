package network.vonix.threadedhorizons.common.threading.worldgen;

import com.google.common.base.Preconditions;
import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.GlobalExecutors;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulingAsyncCombinedLock;
import network.vonix.threadedhorizons.common.threading.scheduler.WorldGenSchedulingState;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class ChunkStatusUtils {

    public static ChunkStatusThreadingType getThreadingType(final ChunkStatus status) {
        return FeatureThreadingPolicy.threadingType(
                status,
                ThreadedHorizonsConfig.threadedWorldGenConfig.allowThreadedFeatures,
                LoaderHooks.isModLoaded(FeatureThreadingPolicy.YUNGS_BRIDGES_MOD_ID)
        );
    }

    public static <T> CompletableFuture<T> runChunkGenWithLock(ChunkPos target, int radius, IntSupplier priority, AsyncNamedLock<ChunkPos> chunkLock, Supplier<CompletableFuture<T>> action) {
        return runChunkGenWithLock(target, radius, priority, chunkLock, action, null, null);
    }

    public static <T> CompletableFuture<T> runChunkGenWithLock(ChunkPos target, int radius, IntSupplier priority, AsyncNamedLock<ChunkPos> chunkLock, Supplier<CompletableFuture<T>> action, ChunkStatus status) {
        return runChunkGenWithLock(target, radius, priority, chunkLock, action, status, null);
    }

    public static <T> CompletableFuture<T> runChunkGenWithLock(ChunkPos target, int radius, IntSupplier priority, AsyncNamedLock<ChunkPos> chunkLock, Supplier<CompletableFuture<T>> action, ChunkStatus status, ChunkHolder holder) {
        Preconditions.checkNotNull(priority);
        Preconditions.checkNotNull(target);
        final BooleanSupplier cancelled = holder == null || status == null
                ? () -> false
                : () -> WorldGenSchedulingState.isCancelled(holder, status);
        if (cancelled.getAsBoolean()) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        if (Thread.currentThread() == SchedulerThread.INSTANCE) {
            return CompletableFuture.supplyAsync(
                    () -> runChunkGenWithLock(target, radius, priority, chunkLock, action, status, holder),
                    GlobalExecutors.executor
            ).thenCompose(Function.identity());
        }

        final CompletableFuture<Void> admission = SchedulerThread.INSTANCE.acquireChunkPipelineAsync(target);
        final CompletableFuture<T> result = new CompletableFuture<>();
        // 0 unclaimed, 1 generation owns slot (startAdmittedChunkGen leaves), 2 cancelled before generation
        final AtomicInteger handoff = new AtomicInteger(0);

        result.whenComplete((ignored, throwable) -> {
            if (result.isCancelled() || throwable instanceof CancellationException) {
                admission.cancel(false);
                handoff.compareAndSet(0, 2);
            }
        });

        admission.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(throwable);
                return;
            }
            if (!handoff.compareAndSet(0, 1)) {
                SchedulerThread.INSTANCE.leaveChunkPipeline(target);
                return;
            }
            if (result.isDone()) {
                SchedulerThread.INSTANCE.leaveChunkPipeline(target);
                return;
            }
            try {
                startAdmittedChunkGen(target, radius, priority, chunkLock, action, cancelled)
                        .whenComplete((value, generated) -> {
                            if (generated != null) {
                                result.completeExceptionally(generated);
                            } else {
                                result.complete(value);
                            }
                        });
            } catch (Throwable started) {
                // startAdmittedChunkGen already leaves in its finally when it fails before
                // transferring leave to the lock future; do not leave twice.
                result.completeExceptionally(started);
            }
        });
        return result;
    }

    private static <T> CompletableFuture<T> startAdmittedChunkGen(ChunkPos target, int radius, IntSupplier priority, AsyncNamedLock<ChunkPos> chunkLock, Supplier<CompletableFuture<T>> action, BooleanSupplier cancelled) {
        boolean releaseImmediately = false;
        try {
            if (SchedulerThread.INSTANCE.isStopping() || cancelled.getAsBoolean()) {
                releaseImmediately = true;
                return CompletableFuture.failedFuture(cancelled.getAsBoolean()
                        ? new CancellationException()
                        : new IllegalStateException("scheduler is stopping"));
            }

            int span = 2 * radius + 1;
            ChunkPos[] fetchedLocks = new ChunkPos[span * span];
            int index = 0;
            for (int x = target.x - radius; x <= target.x + radius; x++) {
                for (int z = target.z - radius; z <= target.z + radius; z++) {
                    fetchedLocks[index++] = new ChunkPos(x, z);
                }
            }

            final SchedulingAsyncCombinedLock<T> lock = new SchedulingAsyncCombinedLock<>(chunkLock, fetchedLocks, priority, SchedulerThread.INSTANCE, action, cancelled);
            if (!SchedulerThread.INSTANCE.addPendingLock(lock)) {
                releaseImmediately = true;
                return lock.getFuture();
            }
            return lock.getFuture().whenComplete((result, throwable) -> SchedulerThread.INSTANCE.leaveChunkPipeline(target));
        } catch (Throwable throwable) {
            releaseImmediately = true;
            return CompletableFuture.failedFuture(throwable);
        } finally {
            if (releaseImmediately) {
                SchedulerThread.INSTANCE.leaveChunkPipeline(target);
            }
        }
    }

    public enum ChunkStatusThreadingType {

        PARALLELIZED() {
            @Override
            public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completableFuture) {
                return CompletableFuture.supplyAsync(completableFuture, GlobalExecutors.executor).thenCompose(Function.identity());
            }
        },
        SINGLE_THREADED() {
            @Override
            public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completableFuture) {
                Preconditions.checkNotNull(lock);
                return lock.acquireLock().toCompletableFuture().thenComposeAsync(lockToken -> {
                    CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> stage;
                    try {
                        stage = completableFuture.get();
                    } catch (Throwable throwable) {
                        lockToken.releaseLock();
                        return CompletableFuture.failedFuture(throwable);
                    }
                    if (stage == null) {
                        lockToken.releaseLock();
                        return CompletableFuture.failedFuture(new NullPointerException("single-thread stage"));
                    }
                    return stage.whenComplete((result, throwable) -> lockToken.releaseLock());
                }, GlobalExecutors.executor);
            }
        },
        AS_IS() {
            @Override
            public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completableFuture) {
                return completableFuture.get();
            }
        };

        public abstract CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completableFuture);

    }
}
