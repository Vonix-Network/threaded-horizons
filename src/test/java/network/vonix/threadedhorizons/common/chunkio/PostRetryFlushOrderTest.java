package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 {@code flushWorker} calls {@code super.flushWorker()} and only then
 * {@code SaveBarrier.awaitQuietAndRetry}. The retry callback can queue a store
 * after that storage flush. Official {@code IOWorker.runStore} completes the
 * store future from {@code RegionFileStorage.write}; {@code RegionFile.write}
 * uses {@code FileChannel.write} and does not call {@code FileChannel.force}.
 * {@code FileChannel.force(true)} is {@code RegionFile.flush}, reached only by
 * {@code IOWorker.synchronize(true)} ({@code lambda$synchronize$7} then
 * {@code lambda$synchronize$6}). {@code ChunkStorage.flushWorker} is that call
 * with {@code iconst_1} followed by {@code join}. Awaiting the retry future is
 * not that fence.
 */
class PostRetryFlushOrderTest {

    @Test
    void flushWorkerFencesRegionStorageAfterTheRetryBarrier() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        int start = source.indexOf("public void flushWorker()");
        assertTrue(start > 0, "flushWorker must exist");
        String body = source.substring(start, source.indexOf("\n    }", start));
        int barrier = body.indexOf("awaitQuietAndRetry");
        int fenceCall = body.indexOf("awaitStorageFence", barrier + 1);
        int sync = fenceCall < 0 ? -1 : body.indexOf("synchronize(true)", fenceCall);
        assertTrue(barrier > 0 && fenceCall > barrier && sync > fenceCall,
                "production flushWorker must pass IOWorker.synchronize(true) to the storage fence after the retry barrier; "
                        + "P4's only super.flushWorker() is before awaitQuietAndRetry: " + body);
    }

    @Test
    void storageFenceFailsClosedWhenTheSynchronizeFutureDoesNotSucceed() {
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
        SaveBarrier.awaitStorageFence(done, SaveBarrier.deadlineAfter(1_000L));

        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("region flush failed"));
        ChunkSaveBarrierException exceptional = assertThrows(ChunkSaveBarrierException.class,
                () -> SaveBarrier.awaitStorageFence(failed, SaveBarrier.deadlineAfter(1_000L)));
        assertTrue(exceptional.getMessage().contains("did not drain"), exceptional.getMessage());

        long started = System.nanoTime();
        CompletableFuture<Void> pending = new CompletableFuture<>();
        ChunkSaveBarrierException expired = assertThrows(ChunkSaveBarrierException.class,
                () -> SaveBarrier.awaitStorageFence(pending, System.nanoTime() - 1L));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2L), "an exhausted deadline must not wait");
        assertTrue(expired.getMessage().contains("did not drain"), expired.getMessage());
        assertTrue(!pending.isCancelled(), "the fence must not cancel a flush that may still finish");

        ChunkSaveBarrierException timedOut = assertThrows(ChunkSaveBarrierException.class,
                () -> SaveBarrier.awaitStorageFence(new CompletableFuture<>(), SaveBarrier.deadlineAfter(50L)));
        assertSame(timedOut.getClass(), ChunkSaveBarrierException.class);

        Thread.currentThread().interrupt();
        try {
            ChunkSaveBarrierException interrupted = assertThrows(ChunkSaveBarrierException.class,
                    () -> SaveBarrier.awaitStorageFence(new CompletableFuture<>(), SaveBarrier.deadlineAfter(5_000L)));
            assertTrue(interrupted.getMessage().contains("interrupted"), interrupted.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
