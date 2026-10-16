package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 matches any {@code IllegalStateException} whose message contains the
 * barrier words, and {@code saveAllChunks} still returns the vanilla boolean.
 * The per-dimension success line is a second loop after every level has
 * returned, so a sticky thread-local also labels a later healthy level as failed.
 */
class SaveFailurePropagationContractTest {

    @AfterEach
    void clearPass() {
        SaveFailureSignal.endPass();
    }

    @Test
    void foreignIllegalStateExceptionWithBarrierWordsIsNotABarrier() {
        SaveFailureSignal.beginPass();
        IllegalStateException foreign = new IllegalStateException(
                "other mod: Chunk save barrier did not drain its own queue");
        assertFalse(SaveFailureSignal.isBarrierFailure(foreign));
        assertFalse(SaveFailureSignal.recordIfBarrier(foreign));
        assertNull(SaveFailureSignal.aggregateFailure());
        IllegalStateException interrupted = new IllegalStateException(
                "other mod: Chunk save barrier interrupted by reload");
        assertFalse(SaveFailureSignal.recordIfBarrier(interrupted));
        assertNull(SaveFailureSignal.aggregateFailure());
        assertTrue(SaveFailureSignal.effectiveSaveSucceeded(true));
    }

    @Test
    void levelAFailureDoesNotMarkHealthyLevelBOrASuccessfulSave() {
        SaveFailureSignal.beginPass();
        SaveFailureSignal.beginLevel();
        ChunkSaveBarrierException failed = new ChunkSaveBarrierException("Chunk save barrier did not drain");
        assertTrue(SaveFailureSignal.recordIfBarrier(failed));
        SaveFailureSignal.beginLevel();
        assertSame(failed, SaveFailureSignal.pollLoggedLevel());
        assertNull(SaveFailureSignal.pollLoggedLevel());
        assertSame(failed, SaveFailureSignal.aggregateFailure());
        assertFalse(SaveFailureSignal.effectiveSaveSucceeded(true));
    }

    @Test
    void continuationCatchIsTheBarrierTypeAndSaveReturnIsForcedFalse() throws Exception {
        String continuation = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinServerLevelSaveContinuation.java"));
        assertFalse(continuation.contains("catch (IllegalStateException"), continuation);
        assertTrue(continuation.contains("catch (ChunkSaveBarrierException"), continuation);

        String report = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinMinecraftServerSaveReport.java"));
        assertTrue(report.contains("setReturnValue(false)"), report);
        assertTrue(report.contains("pollLoggedLevel()"), report);
        assertFalse(report.contains("currentFailure()"),
                "the per-dimension log must not use the sticky pass-wide failure");
    }
}
