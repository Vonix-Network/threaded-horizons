package network.vonix.threadedhorizons.common.chunkio;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Shutdown drain for in-flight stores and for stores that already failed.
 * One deadline covers the quiet join, every retry, and the storage fence the
 * caller waits on after those retries. The scheduler runs on the caller
 * thread, outside {@code managedBlock}, so a completion that needs the server
 * thread is not joined while that thread is blocked in the wait. Pending
 * state is the caller's supplier, so one dimension's barrier cannot observe
 * another dimension's failed chunks.
 */
public final class SaveBarrier {

    private SaveBarrier() {
    }

    public static long deadlineAfter(long timeoutMs) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
    }

    /**
     * @param hasPending owner-scoped view of failed stores. A null supplier is
     *                   treated as nothing pending.
     * @param scheduleRetries returns true when it queued a retry or left one
     *                        in flight. Returns false when nothing could be
     *                        scheduled and failed chunks are still pending.
     */
    public static void awaitQuietAndRetry(
            SaveFutureTracker tracker,
            long timeoutMs,
            BooleanSupplier hasPending,
            BooleanSupplier scheduleRetries
    ) {
        awaitQuietAndRetryUntil(tracker, deadlineAfter(timeoutMs), hasPending, scheduleRetries);
    }

    public static void awaitQuietAndRetryUntil(
            SaveFutureTracker tracker,
            long deadlineNanos,
            BooleanSupplier hasPending,
            BooleanSupplier scheduleRetries
    ) {
        if (tracker == null) {
            throw new ChunkSaveBarrierException("Chunk save barrier did not drain");
        }
        while (true) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new ChunkSaveBarrierException("Chunk save barrier interrupted");
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0L) {
                throw new ChunkSaveBarrierException("Chunk save barrier did not drain");
            }
            if (!tracker.joinFully(remainingMs)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ChunkSaveBarrierException("Chunk save barrier interrupted");
                }
                throw new ChunkSaveBarrierException("Chunk save barrier did not drain");
            }
            if (hasPending == null || !hasPending.getAsBoolean()) {
                return;
            }
            boolean scheduled = scheduleRetries != null && scheduleRetries.getAsBoolean();
            if (!hasPending.getAsBoolean()) {
                return;
            }
            if (!scheduled) {
                throw new ChunkSaveBarrierException("Chunk save barrier did not drain");
            }
        }
    }

    /**
     * Wait for the storage fence that follows a retry. Official
     * {@code IOWorker.synchronize(true)} completes only after
     * {@code RegionFileStorage.flush}. A timeout, an interrupt, or an
     * exceptional result is a barrier failure. The future is not cancelled.
     */
    public static void awaitStorageFence(Future<?> synchronizeFlush, long deadlineNanos) {
        if (synchronizeFlush == null) {
            throw new ChunkSaveBarrierException("Chunk save barrier did not drain");
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new ChunkSaveBarrierException("Chunk save barrier did not drain");
        }
        try {
            synchronizeFlush.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new ChunkSaveBarrierException("Chunk save barrier did not drain", timeout);
        } catch (ExecutionException failure) {
            throw new ChunkSaveBarrierException("Chunk save barrier did not drain", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ChunkSaveBarrierException("Chunk save barrier interrupted", interrupted);
        }
    }
}
