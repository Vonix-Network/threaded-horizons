package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dimension A's failed store is recorded on A's registry. Dimension B's retry
 * drains only B's registry and B's barrier does not schedule A's chunk.
 * On P3 both dimensions shared {@code FailedChunkSaves.PENDING}, and B's
 * {@code drain()} returned A's chunk.
 */
class CrossDimensionFailureIsolationTest {

    @Test
    void otherDimensionMustNotDrainOrRetryAForeignFailure() {
        FailedChunkSaves dimensionA = new FailedChunkSaves();
        FailedChunkSaves dimensionB = new FailedChunkSaves();
        Object chunkFromA = new Object();
        dimensionA.record(chunkFromA);

        List<Object> writesByB = new ArrayList<>();
        for (Object pending : dimensionB.drain()) {
            writesByB.add(pending);
        }
        assertTrue(writesByB.isEmpty(),
                "dimension B drained dimension A's failed chunk and would store it through B's worker: " + writesByB);
        assertTrue(dimensionA.hasPending(), "A must still own the failed chunk");
        assertFalse(dimensionB.hasPending());

        SaveFutureTracker trackerB = new SaveFutureTracker();
        List<Object> retriedByB = new ArrayList<>();
        SaveBarrier.awaitQuietAndRetry(trackerB, 1_000L, dimensionB::hasPending, () -> {
            retriedByB.addAll(dimensionB.drain());
            return !retriedByB.isEmpty();
        });
        assertTrue(retriedByB.isEmpty(),
                "dimension B's barrier retried dimension A's chunk: " + retriedByB);
        List<Object> stillOnA = dimensionA.drain();
        assertTrue(stillOnA.size() == 1, stillOnA.toString());
        assertSame(chunkFromA, stillOnA.get(0));
        assertFalse(dimensionA.hasPending());
        assertFalse(dimensionB.hasPending());
    }
}
