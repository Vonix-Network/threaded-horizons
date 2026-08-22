package network.vonix.threadedhorizons.common.threading.scheduler;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenSchedulingStateTest {

    @Test
    void cancellationMarkerRequiresCompletedUnloadResult() {
        assertFalse(WorldGenSchedulingState.isCancelledFuture(null));
        assertFalse(WorldGenSchedulingState.isCancelledFuture(new CompletableFuture<>()));

        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> exceptional = new CompletableFuture<>();
        exceptional.completeExceptionally(new IllegalStateException("generation failed"));
        assertFalse(WorldGenSchedulingState.isCancelledFuture(exceptional));

        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        assertFalse(WorldGenSchedulingState.isCancelledFuture(cancelled));

        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> unloaded =
                CompletableFuture.completedFuture(Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED));
        assertTrue(WorldGenSchedulingState.isCancelledFuture(unloaded));
    }

    @Test
    void takeChunkHolderClearsThreadLocal() {
        WorldGenSchedulingState.clearChunkHolder();
        assertNull(WorldGenSchedulingState.takeChunkHolder());
        WorldGenSchedulingState.setChunkHolder(null);
        assertNull(WorldGenSchedulingState.getChunkHolder());
        assertNull(WorldGenSchedulingState.takeChunkHolder());
        assertNull(WorldGenSchedulingState.getChunkHolder());
    }
}
