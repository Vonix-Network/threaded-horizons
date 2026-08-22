package network.vonix.threadedhorizons.common.threading.scheduler;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Carries the holder for the synchronous Forge callback that invokes
 * {@code ChunkStatus.generate}. The value is scoped to that callback thread
 * and is always cleared by the companion mixin.
 */
public final class WorldGenSchedulingState {
    private static final ThreadLocal<ChunkHolder> CURRENT_HOLDER = new ThreadLocal<>();

    private WorldGenSchedulingState() {
    }

    public static ChunkHolder getChunkHolder() {
        return CURRENT_HOLDER.get();
    }

    /**
     * Returns the holder captured for this generate callback and clears the
     * thread-local so a later reuse of the worker cannot observe a stale value.
     */
    public static ChunkHolder takeChunkHolder() {
        ChunkHolder holder = CURRENT_HOLDER.get();
        CURRENT_HOLDER.remove();
        return holder;
    }

    public static void setChunkHolder(ChunkHolder holder) {
        CURRENT_HOLDER.set(holder);
    }

    public static void clearChunkHolder() {
        CURRENT_HOLDER.remove();
    }

    public static boolean isCancelled(ChunkHolder holder, ChunkStatus status) {
        if (holder == null || status == null) {
            return false;
        }
        for (Pair<ChunkStatus, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> entry : holder.getAllFutures()) {
            if (entry.getFirst() == status) {
                return isCancelledFuture(entry.getSecond());
            }
        }
        return false;
    }

    static boolean isCancelledFuture(CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
            return false;
        }
        final Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = future.getNow(null);
        return result != null && result.right().isPresent();
    }
}
