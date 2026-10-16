package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chunk-barrier failure is continued and reported per level. The aggregate
 * makes {@code saveAllChunks}' vanilla true mean failure. Any other throwable
 * is not recorded. {@code beginPass} isolates one pass from the previous one.
 */
class SaveContinuationSignalTest {

    @AfterEach
    void clearPass() {
        SaveFailureSignal.endPass();
    }

    @Test
    void barrierFailureIsContinuedAndWithholdsSuccessUntilTheNextPass() {
        SaveFailureSignal.beginPass();
        assertNull(SaveFailureSignal.aggregateFailure());
        assertFalse(SaveFailureSignal.recordIfBarrier(new IllegalStateException("disk full")));
        assertNull(SaveFailureSignal.aggregateFailure());
        SaveFailureSignal.beginLevel();
        ChunkSaveBarrierException barrier = new ChunkSaveBarrierException("Chunk save barrier did not drain");
        assertTrue(SaveFailureSignal.recordIfBarrier(barrier));
        assertSame(barrier, SaveFailureSignal.aggregateFailure());
        SaveFailureSignal.beginLevel();
        ChunkSaveBarrierException later = new ChunkSaveBarrierException("Chunk save barrier interrupted");
        assertTrue(SaveFailureSignal.recordIfBarrier(later));
        assertSame(barrier, SaveFailureSignal.aggregateFailure());
        assertSame(barrier, SaveFailureSignal.pollLoggedLevel());
        assertSame(later, SaveFailureSignal.pollLoggedLevel());
        assertFalse(SaveFailureSignal.effectiveSaveSucceeded(true));
        SaveFailureSignal.beginPass();
        assertNull(SaveFailureSignal.aggregateFailure());
        assertTrue(SaveFailureSignal.effectiveSaveSucceeded(true));
        assertFalse(SaveFailureSignal.effectiveSaveSucceeded(false));
    }

    @Test
    void unrelatedThrowablesDoNotCountAsABarrier() {
        SaveFailureSignal.beginPass();
        SaveFailureSignal.beginLevel();
        assertFalse(SaveFailureSignal.recordIfBarrier(new IllegalStateException("chunk save future queue is full")));
        assertFalse(SaveFailureSignal.recordIfBarrier(new IllegalStateException("Chunk save barrier did not drain")));
        assertFalse(SaveFailureSignal.recordIfBarrier(new RuntimeException("Chunk save barrier did not drain")));
        assertFalse(SaveFailureSignal.recordIfBarrier(new IllegalStateException("Chunk save barrier interrupted")));
        assertFalse(SaveFailureSignal.recordIfBarrier(null));
        assertNull(SaveFailureSignal.aggregateFailure());
        assertNull(SaveFailureSignal.pollLoggedLevel());
        assertTrue(SaveFailureSignal.effectiveSaveSucceeded(true));
    }

    @Test
    void failedLevelDoesNotRelabelALaterHealthyLevel() {
        SaveFailureSignal.beginPass();
        SaveFailureSignal.beginLevel();
        ChunkSaveBarrierException failed = new ChunkSaveBarrierException("Chunk save barrier did not drain");
        assertTrue(SaveFailureSignal.recordIfBarrier(failed));
        SaveFailureSignal.beginLevel();
        assertFalse(SaveFailureSignal.recordIfBarrier(
                new IllegalStateException("other mod: Chunk save barrier did not drain")));
        assertSame(failed, SaveFailureSignal.pollLoggedLevel());
        assertNull(SaveFailureSignal.pollLoggedLevel());
        assertNull(SaveFailureSignal.pollLoggedLevel());
        assertSame(failed, SaveFailureSignal.aggregateFailure());
        assertFalse(SaveFailureSignal.effectiveSaveSucceeded(true));
        assertFalse(SaveFailureSignal.effectiveSaveSucceeded(false));
    }

    @Test
    void anotherThreadDoesNotObserveThisThreadsFailure() throws Exception {
        SaveFailureSignal.beginPass();
        SaveFailureSignal.beginLevel();
        ChunkSaveBarrierException barrier = new ChunkSaveBarrierException("Chunk save barrier did not drain");
        assertTrue(SaveFailureSignal.recordIfBarrier(barrier));
        boolean[] seen = {true};
        Thread other = new Thread(() -> seen[0] = SaveFailureSignal.aggregateFailure() != null, "other-dimension");
        other.start();
        other.join(2_000L);
        assertFalse(other.isAlive());
        assertFalse(seen[0], "a barrier failure must not be visible to another thread's save");
        assertSame(barrier, SaveFailureSignal.aggregateFailure());
    }
}
