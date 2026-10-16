package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two completions captured at the same generation. The first durable
 * completion must not recycle that number, and the second must not clear a
 * mutation that arrived after the first completion.
 */
@Timeout(30)
class ReusedGenerationStaleCompletionTest {

    @Test
    void directKeyStaleCompletionMustNotClearAReusedGeneration() {
        assertStaleCompletionDoesNotClearReusedGeneration(new Object(), "Object");
    }

    @Test
    void holderStaleCompletionMustNotClearAReusedGeneration() {
        assertStaleCompletionDoesNotClearReusedGeneration(new Holder(), "Holder");
    }

    @Test
    void nestedMutationDuringClearIsNotReportedCleared() {
        assertNestedMutationSurvivesClear(new Object());
        assertNestedMutationSurvivesClear(new Holder());
    }

    private static void assertNestedMutationSurvivesClear(Object chunk) {
        AtomicBoolean unsaved = new AtomicBoolean(true);
        try {
            long generation = DirtyChunkGenerations.markMutated(chunk);
            boolean cleared = DirtyChunkGenerations.transitionAfterSave(
                    chunk,
                    generation,
                    true,
                    () -> {
                        unsaved.set(false);
                        DirtyChunkGenerations.markMutated(chunk);
                        unsaved.set(true);
                    },
                    unsaved::get,
                    () -> unsaved.set(true));
            assertFalse(cleared, "a mutation nested inside clearUnsaved must not be acknowledged as saved");
            assertTrue(unsaved.get());
            assertTrue(DirtyChunkGenerations.current(chunk) > generation);
        } finally {
            DirtyChunkGenerations.forget(chunk);
        }
    }

    private static void assertStaleCompletionDoesNotClearReusedGeneration(Object chunk, String label) {
        AtomicBoolean unsaved = new AtomicBoolean(true);
        try {
            long first = DirtyChunkGenerations.markMutated(chunk);
            long captured = DirtyChunkGenerations.captureForSave(chunk);
            long capturedAgain = DirtyChunkGenerations.captureForSave(chunk);
            assertEquals(1L, first, label);
            assertEquals(first, captured, label);
            assertEquals(first, capturedAgain, label);

            assertTrue(DirtyChunkGenerations.applyStoreOutcome(chunk, captured, true, unsaved), label);
            assertFalse(unsaved.get(), label);
            assertEquals(first, DirtyChunkGenerations.current(chunk),
                    label + " durable success must not recycle the generation");

            unsaved.set(true);
            long newer = DirtyChunkGenerations.markMutated(chunk);
            boolean staleClear = DirtyChunkGenerations.applyStoreOutcome(chunk, capturedAgain, true, unsaved);
            assertFalse(staleClear, label
                    + " old save incorrectly acknowledged new mutation"
                    + " old=" + capturedAgain
                    + " newer=" + newer
                    + " current=" + DirtyChunkGenerations.current(chunk)
                    + " staleClear=" + staleClear
                    + " unsaved=" + unsaved.get());
            assertTrue(unsaved.get(), label + " stale completion cleared unsaved");
            assertTrue(newer > capturedAgain, label + " newer generation reused the stale save generation");
            assertEquals(newer, DirtyChunkGenerations.current(chunk), label);
        } finally {
            DirtyChunkGenerations.forget(chunk);
        }
    }

    private static final class Holder implements DirtyGenerationHolder {
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
