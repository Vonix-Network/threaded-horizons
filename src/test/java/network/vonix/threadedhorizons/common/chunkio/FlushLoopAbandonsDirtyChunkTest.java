package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Negative control for the 1.1.1 flush-loop loss. Vanilla
 * {@code saveAllChunks(true)} calls save until it returns false. A full
 * save-future cap used to make that call return false while the chunk was
 * still unsaved and had no store queued.
 */
@Timeout(30)
class FlushLoopAbandonsDirtyChunkTest {

    @Test
    void fullFutureCapDoesNotAbandonDirtyChunkWhenASlotFrees() throws Exception {
        SaveFutureTracker tracker = new SaveFutureTracker();
        List<CompletableFuture<Void>> blockers = new ArrayList<>();
        for (int index = 0; index < SaveFutureTracker.MAX_SAVE_FUTURES; index++) {
            assertTrue(tracker.tryReserve());
            CompletableFuture<Void> blocker = new CompletableFuture<>();
            tracker.attach(blocker);
            blockers.add(blocker);
        }

        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        DirtyChunkGenerations.markMutated(chunk);
        List<CompletableFuture<Void>> queued = new ArrayList<>();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(40L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            blockers.get(0).complete(null);
        }, "free-save-slot");
        releaser.start();
        try {
            boolean progressed;
            do {
                progressed = saveLikeCandidate(tracker, chunk, unsaved, queued, 5_000L);
            } while (progressed);

            assertEquals(1, queued.size(), "the dirty generation is stored once");
            assertTrue(unsaved.get(), "unsaved stays set until that store completes");
            assertTrue(ChunkSaveAdmission.isAdmitted(chunk, DirtyChunkGenerations.current(chunk)));
            queued.get(0).complete(null);
            assertFalse(unsaved.get(), "durable completion of the current generation clears unsaved");
            assertEquals(1L, DirtyChunkGenerations.current(chunk),
                    "durable success must not recycle generation 1");
            assertFalse(ChunkSaveAdmission.isAdmitted(chunk, 1L));
        } finally {
            releaser.join(5_000L);
            for (CompletableFuture<Void> blocker : blockers) {
                blocker.complete(null);
            }
            for (CompletableFuture<Void> stored : queued) {
                stored.complete(null);
            }
            DirtyChunkGenerations.forget(chunk);
        }
    }

    @Test
    void directKeyDoesNotAdmitTheSameGenerationTwice() {
        assertSameGenerationCoalesces(new Object());
    }

    @Test
    void holderDoesNotAdmitTheSameGenerationTwice() {
        assertSameGenerationCoalesces(new FakeHolder());
    }

    @Test
    void newerGenerationStillAdmitsAndOlderCompletionDoesNotClearIt() {
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        SaveFutureTracker tracker = new SaveFutureTracker();
        List<CompletableFuture<Void>> queued = new ArrayList<>();
        try {
            DirtyChunkGenerations.markMutated(chunk);
            assertTrue(saveLikeCandidate(tracker, chunk, unsaved, queued, 1_000L));
            long first = DirtyChunkGenerations.current(chunk);
            long newer = DirtyChunkGenerations.markMutated(chunk);
            unsaved.set(true);
            assertTrue(newer > first);
            assertTrue(saveLikeCandidate(tracker, chunk, unsaved, queued, 1_000L));
            assertEquals(2, queued.size());
            assertFalse(saveLikeCandidate(tracker, chunk, unsaved, queued, 1_000L),
                    "a third call while the newer generation is admitted must not take another slot");
            queued.get(0).complete(null);
            assertTrue(unsaved.get(), "older completion must not clear the newer mutation");
            assertEquals(newer, DirtyChunkGenerations.current(chunk));
            queued.get(1).complete(null);
            assertFalse(unsaved.get());
            assertEquals(newer, DirtyChunkGenerations.current(chunk));
        } finally {
            for (CompletableFuture<Void> stored : queued) {
                stored.complete(null);
            }
            DirtyChunkGenerations.forget(chunk);
        }
    }

    @Test
    void failedStoreReleasesAdmissionAndRetryHonorsSetUnsaved() {
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        SaveFutureTracker tracker = new SaveFutureTracker();
        List<CompletableFuture<Void>> queued = new ArrayList<>();
        try {
            long generation = DirtyChunkGenerations.markMutated(chunk);
            assertTrue(saveLikeCandidate(tracker, chunk, unsaved, queued, 1_000L));
            queued.get(0).completeExceptionally(new IllegalStateException("disk"));
            assertTrue(unsaved.get(), "a failed store leaves a still-dirty chunk unsaved");
            assertFalse(ChunkSaveAdmission.isAdmitted(chunk, generation));
            assertTrue(saveLikeCandidate(tracker, chunk, unsaved, queued, 1_000L),
                    "ending the failed admission lets the same generation retry");

            AtomicBoolean clearedEarly = new AtomicBoolean(false);
            assertFalse(DirtyChunkGenerations.applyStoreOutcome(chunk, generation, false, clearedEarly));
            assertTrue(clearedEarly.get(), "failure re-marks a chunk whose unsaved flag was already cleared");
            long retried = DirtyChunkGenerations.markMutated(chunk);
            assertTrue(retried > generation);
            clearedEarly.set(true);
            assertFalse(DirtyChunkGenerations.applyStoreOutcome(chunk, generation, true, clearedEarly),
                    "a stale success must not clear the setUnsaved retry");
            assertTrue(clearedEarly.get());
            assertTrue(DirtyChunkGenerations.applyStoreOutcome(chunk, retried, true, clearedEarly));
            assertFalse(clearedEarly.get());
        } finally {
            for (CompletableFuture<Void> stored : queued) {
                stored.complete(null);
            }
            DirtyChunkGenerations.forget(chunk);
        }
    }

    @Test
    void saveOverwriteCoalescesBeforeReservingAFuture() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        int begin = source.indexOf("ChunkSaveAdmission.begin");
        int reserve = source.indexOf("saveTracker.tryReserve()");
        assertTrue(begin > 0 && reserve > begin, source);
        assertTrue(source.contains("ChunkSaveAdmission.end"), source);
        assertTrue(source.contains("saveTracker.awaitRoom("), source);
        assertTrue(source.contains("IllegalStateException(\"chunk save future queue is full\")"), source);
    }

    private static void assertSameGenerationCoalesces(Object chunk) {
        AtomicBoolean unsaved = new AtomicBoolean(true);
        SaveFutureTracker tracker = new SaveFutureTracker();
        List<CompletableFuture<Void>> queued = new ArrayList<>();
        try {
            long generation = DirtyChunkGenerations.markMutated(chunk);
            assertEquals(1L, generation);
            assertTrue(saveLikeCandidate(tracker, chunk, unsaved, queued, 1_000L));
            assertFalse(saveLikeCandidate(tracker, chunk, unsaved, queued, 1_000L));
            assertEquals(1, queued.size());
            assertEquals(1, tracker.inFlight());
            assertTrue(ChunkSaveAdmission.isAdmitted(chunk, generation));
            queued.get(0).complete(null);
            assertFalse(unsaved.get());
            assertEquals(generation, DirtyChunkGenerations.current(chunk));
            assertFalse(ChunkSaveAdmission.isAdmitted(chunk, generation));
        } finally {
            for (CompletableFuture<Void> stored : queued) {
                stored.complete(null);
            }
            DirtyChunkGenerations.forget(chunk);
        }
    }

    /**
     * Helper-only copy of the admission policy in
     * {@code MixinThreadedAnvilChunkStorage#save}. It is not a production
     * mixin execution gate. {@code SavePathBytecodeTest} disassembles the
     * compiled mixin and generation helper; a Forge runtime apply of the
     * mixin is not covered here.
     */
    private static boolean saveLikeCandidate(
            SaveFutureTracker tracker,
            Object chunk,
            AtomicBoolean unsaved,
            List<CompletableFuture<Void>> queued,
            long waitBudgetMs
    ) {
        if (!unsaved.get()) {
            return false;
        }
        long generation = DirtyChunkGenerations.captureForSave(chunk);
        if (!ChunkSaveAdmission.begin(chunk, generation)) {
            return false;
        }
        boolean handedToCallback = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitBudgetMs);
            while (!tracker.tryReserve()) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0L || !tracker.awaitRoom(remainingMs)) {
                    throw new IllegalStateException("chunk save future queue is full");
                }
            }
            CompletableFuture<Void> stored = new CompletableFuture<>();
            tracker.attach(stored);
            long admittedGeneration = generation;
            stored.whenComplete((unused, throwable) -> {
                try {
                    DirtyChunkGenerations.applyStoreOutcome(chunk, admittedGeneration, throwable == null, unsaved);
                } finally {
                    ChunkSaveAdmission.end(chunk, admittedGeneration);
                }
            });
            handedToCallback = true;
            queued.add(stored);
            return true;
        } catch (RuntimeException failure) {
            if (!handedToCallback) {
                ChunkSaveAdmission.end(chunk, generation);
            }
            DirtyChunkGenerations.applyStoreOutcome(chunk, generation, false, unsaved);
            return false;
        }
    }

    private static final class FakeHolder implements DirtyGenerationHolder {
        private final AtomicLong generation = new AtomicLong();
        private final Object lock = new Object();

        @Override
        public long markDirtyGeneration() {
            return this.generation.incrementAndGet();
        }

        @Override
        public long currentDirtyGeneration() {
            return this.generation.get();
        }

        @Override
        public void forgetDirtyGeneration() {
            this.generation.set(0L);
        }

        @Override
        public Object dirtyGenerationLock() {
            return this.lock;
        }
    }
}
