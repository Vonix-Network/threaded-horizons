package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class DirtyChunkGenerationsTest {

    @AfterEach
    void resetHooks() {
        DirtyChunkGenerations.hooks.reset();
    }

    @Test
    void saveCapturesMutationGenerationAndDoesNotAdvanceOnRetry() {
        Object chunk = new Object();
        long mutation = DirtyChunkGenerations.markMutated(chunk);
        long firstSave = DirtyChunkGenerations.captureForSave(chunk);
        long secondSave = DirtyChunkGenerations.captureForSave(chunk);
        assertEquals(mutation, firstSave);
        assertEquals(mutation, secondSave);
        assertTrue(DirtyChunkGenerations.isCurrent(chunk, firstSave));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void captureForSaveDoesNotCreateOrAdvanceGeneration() {
        Object chunk = new Object();
        assertEquals(0L, DirtyChunkGenerations.captureForSave(chunk));
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
        assertEquals(0L, DirtyChunkGenerations.captureForSave(chunk));
        long mutation = DirtyChunkGenerations.markMutated(chunk);
        assertEquals(1L, mutation);
        assertEquals(mutation, DirtyChunkGenerations.captureForSave(chunk));
        assertEquals(mutation, DirtyChunkGenerations.captureForSave(chunk));
        assertEquals(mutation, DirtyChunkGenerations.current(chunk));
        DirtyChunkGenerations.forget(chunk);
        assertEquals(0L, DirtyChunkGenerations.captureForSave(chunk));
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
    }

    @Test
    void mutationDuringSerializationKeepsNewerGenerationDirtyForRetry() {
        Object chunk = new Object();
        long firstMutation = DirtyChunkGenerations.markMutated(chunk);
        long saveGeneration = DirtyChunkGenerations.captureForSave(chunk);
        assertEquals(firstMutation, saveGeneration);

        long overlappingMutation = DirtyChunkGenerations.markMutated(chunk);
        assertTrue(overlappingMutation > saveGeneration);
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, saveGeneration, true),
                "older durable completion must not clear a newer mutation");
        assertFalse(DirtyChunkGenerations.isCurrent(chunk, saveGeneration));
        assertEquals(overlappingMutation, DirtyChunkGenerations.current(chunk));

        long retryGeneration = DirtyChunkGenerations.captureForSave(chunk);
        assertEquals(overlappingMutation, retryGeneration);
        assertTrue(DirtyChunkGenerations.shouldClearUnsaved(chunk, retryGeneration, true));
        DirtyChunkGenerations.forget(chunk);
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, retryGeneration, true));
    }

    @Test
    void failedOlderStoreDoesNotClearNewerMutation() {
        Object chunk = new Object();
        long saveGeneration = DirtyChunkGenerations.captureForSave(chunk);
        DirtyChunkGenerations.markMutated(chunk);
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, saveGeneration, false));
        assertTrue(DirtyChunkGenerations.current(chunk) > saveGeneration);
        long retryGeneration = DirtyChunkGenerations.captureForSave(chunk);
        assertTrue(DirtyChunkGenerations.shouldClearUnsaved(chunk, retryGeneration, true));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void overlappingAutosaveUnloadAndShutdownKeepLatestMutation() {
        Object chunk = new Object();
        long mutation = DirtyChunkGenerations.markMutated(chunk);
        long autosave = DirtyChunkGenerations.captureForSave(chunk);
        DirtyChunkGenerations.markMutated(chunk);
        long unload = DirtyChunkGenerations.captureForSave(chunk);
        DirtyChunkGenerations.markMutated(chunk);
        long shutdown = DirtyChunkGenerations.captureForSave(chunk);
        assertEquals(mutation, autosave);
        assertTrue(unload > autosave);
        assertTrue(shutdown > unload);
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, autosave, true));
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, unload, true));
        assertTrue(DirtyChunkGenerations.shouldClearUnsaved(chunk, shutdown, true));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void successfulSaveClearsOnlyWhenGenerationIsStillCurrent() {
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        long generation = DirtyChunkGenerations.markMutated(chunk);
        assertTrue(DirtyChunkGenerations.applyStoreOutcome(chunk, generation, true, unsaved));
        assertFalse(unsaved.get());
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void mutationBetweenValidationAndClearKeepsNewerDirtyState() throws Exception {
        assertMutationBetweenValidationAndClearSurvives("direct");
    }

    @Test
    void ephemeralCaptureKeysDoNotGrowLockStorageBeyondFixedStripes() {
        int stripes = DirtyChunkGenerations.lockStripeCount();
        assertEquals(DirtyChunkGenerations.LOCK_STRIPE_COUNT, stripes);
        for (int index = 0; index < stripes * 32; index++) {
            Object ephemeral = new Object();
            assertEquals(0L, DirtyChunkGenerations.captureForSave(ephemeral));
            assertEquals(0L, DirtyChunkGenerations.current(ephemeral));
            DirtyChunkGenerations.forget(ephemeral);
        }
        assertEquals(stripes, DirtyChunkGenerations.lockStripeCount(),
                "lock storage must remain the fixed stripe count");
    }

    @Test
    void forgetRemovesGenerationEntry() {
        int before = DirtyChunkGenerations.generationEntryCount();
        Object chunk = new Object();
        DirtyChunkGenerations.markMutated(chunk);
        assertEquals(before + 1, DirtyChunkGenerations.generationEntryCount());
        DirtyChunkGenerations.forget(chunk);
        assertEquals(before, DirtyChunkGenerations.generationEntryCount());
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
    }

    @Test
    void holderPathDoesNotGrowStripeMapAndKeepsDurabilityContract() throws Exception {
        int before = DirtyChunkGenerations.generationEntryCount();
        FakeHolder chunk = new FakeHolder();
        long mutation = DirtyChunkGenerations.markMutated(chunk);
        assertEquals(1L, mutation);
        assertEquals(before, DirtyChunkGenerations.generationEntryCount(),
                "per-chunk holder storage must not insert a WeakHashMap stripe entry");
        AtomicBoolean unsaved = new AtomicBoolean(true);
        assertTrue(DirtyChunkGenerations.applyStoreOutcome(chunk, mutation, true, unsaved));
        assertFalse(unsaved.get());
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
        DirtyChunkGenerationsTest.assertMutationBetweenValidationAndClearSurvivesHolder();
    }

    static void assertMutationBetweenValidationAndClearSurvivesHolder() throws Exception {
        FakeHolder chunk = new FakeHolder();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        long saveGeneration = DirtyChunkGenerations.markMutated(chunk);
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch mutated = new CountDownLatch(1);
        DirtyChunkGenerations.hooks.afterValidationBeforeClear = (key, observed) -> {
            assertEquals(chunk, key);
            assertEquals(saveGeneration, observed);
            validated.countDown();
            try {
                if (!mutated.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeException("holder interleave latch timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
        };
        try {
            CompletableFuture<Boolean> cleared = CompletableFuture.supplyAsync(
                    () -> DirtyChunkGenerations.applyStoreOutcome(chunk, saveGeneration, true, unsaved));
            assertTrue(validated.await(5, TimeUnit.SECONDS));
            long overlapping = DirtyChunkGenerations.markMutated(chunk);
            unsaved.set(true);
            mutated.countDown();
            assertFalse(cleared.get(5, TimeUnit.SECONDS));
            assertTrue(unsaved.get());
            assertEquals(overlapping, DirtyChunkGenerations.current(chunk));
        } finally {
            DirtyChunkGenerations.hooks.reset();
            DirtyChunkGenerations.forget(chunk);
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

    @Test
    void raceMarkMutatedApplyStoreAndForgetNeverClearsNewerMutation() throws Exception {
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        AtomicLong newestSeen = new AtomicLong();
        AtomicInteger illegalClear = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CyclicBarrier start = new CyclicBarrier(6);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int worker = 0; worker < 3; worker++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    awaitBarrier(start);
                    for (int i = 0; i < 200; i++) {
                        long generation = DirtyChunkGenerations.markMutated(chunk);
                        newestSeen.accumulateAndGet(generation, Math::max);
                        unsaved.set(true);
                    }
                }, pool));
            }
            futures.add(CompletableFuture.runAsync(() -> {
                awaitBarrier(start);
                for (int i = 0; i < 200; i++) {
                    long capture = DirtyChunkGenerations.captureForSave(chunk);
                    // Observe generation under the same serialization window used by clear.
                    DirtyChunkGenerations.hooks.afterValidationBeforeClear = (key, observed) -> {
                        if (capture != 0L && observed > capture) {
                            // Older capture must not clear after a newer mutation is visible.
                            // applyStoreOutcome itself enforces this; count attempts that would be illegal.
                            if (DirtyChunkGenerations.shouldClearUnsaved(chunk, capture, true)) {
                                illegalClear.incrementAndGet();
                            }
                        }
                    };
                    DirtyChunkGenerations.applyStoreOutcome(chunk, capture, true, unsaved);
                }
            }, pool));
            futures.add(CompletableFuture.runAsync(() -> {
                awaitBarrier(start);
                for (int i = 0; i < 200; i++) {
                    DirtyChunkGenerations.forget(chunk);
                }
            }, pool));
            futures.add(CompletableFuture.runAsync(() -> {
                awaitBarrier(start);
                for (int i = 0; i < 200; i++) {
                    DirtyChunkGenerations.current(chunk);
                }
            }, pool));
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            DirtyChunkGenerations.hooks.reset();
            assertEquals(0, illegalClear.get(), "newer mutation must never be cleared by older outcome");
            long finalGeneration = DirtyChunkGenerations.markMutated(chunk);
            assertTrue(finalGeneration > 0L);
            assertTrue(DirtyChunkGenerations.isCurrent(chunk, finalGeneration));
            assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, finalGeneration - 1, true));
            // Deterministic interleave already covered by mutationBetweenValidationAndClear.
            assertMutationBetweenValidationAndClearSurvives("race-followup");
        } finally {
            pool.shutdownNow();
            DirtyChunkGenerations.hooks.reset();
            DirtyChunkGenerations.forget(chunk);
        }
    }

    public static void assertMutationBetweenValidationAndClearSurvives(String profile) throws Exception {
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        long saveGeneration = DirtyChunkGenerations.markMutated(chunk);
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch mutated = new CountDownLatch(1);
        AtomicLong newerGeneration = new AtomicLong();
        DirtyChunkGenerations.hooks.afterValidationBeforeClear = (key, observed) -> {
            assertEquals(chunk, key, profile);
            assertEquals(saveGeneration, observed, profile);
            validated.countDown();
            awaitLatch(mutated, profile);
        };
        try {
            CompletableFuture<Boolean> cleared = CompletableFuture.supplyAsync(
                    () -> DirtyChunkGenerations.applyStoreOutcome(chunk, saveGeneration, true, unsaved));
            assertTrue(validated.await(5, TimeUnit.SECONDS), profile + " validation hook");
            long overlapping = DirtyChunkGenerations.markMutated(chunk);
            newerGeneration.set(overlapping);
            unsaved.set(true);
            mutated.countDown();
            assertFalse(cleared.get(5, TimeUnit.SECONDS), profile + " must not clear after overlapping mutation");
            assertTrue(unsaved.get(), profile + " must remain dirty");
            assertEquals(overlapping, DirtyChunkGenerations.current(chunk), profile);
            assertTrue(DirtyChunkGenerations.isCurrent(chunk, overlapping), profile);
            assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, saveGeneration, true), profile);
        } finally {
            DirtyChunkGenerations.hooks.reset();
            DirtyChunkGenerations.forget(chunk);
        }
    }

    private static void awaitLatch(CountDownLatch latch, String profile) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException(profile + " dirty-generation interleave latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
