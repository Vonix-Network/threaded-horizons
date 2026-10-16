package network.vonix.threadedhorizons.mixin.threading.chunkio;

import network.vonix.threadedhorizons.common.chunkio.ChunkSaveBarrierException;
import network.vonix.threadedhorizons.common.chunkio.SaveFailureSignal;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Official {@code ServerLevel.save} has no handler between
 * {@code ServerChunkCache.save} and {@code entityManager.saveAll}/{@code autoSave}.
 * Only {@link ChunkSaveBarrierException} is caught, so entity writes still run.
 * Any other exception, including an unrelated {@code IllegalStateException},
 * still propagates. The failed chunk stays pending on the chunk map that owns it.
 */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevelSaveContinuation {

    private static final Logger TH_SAVE_LOG = LoggerFactory.getLogger("Threaded Horizons");

    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V", at = @At("HEAD"))
    private void threadedHorizons$beginLevel(ProgressListener listener, boolean flush, boolean skip, CallbackInfo ci) {
        SaveFailureSignal.beginLevel();
    }

    @Redirect(
            method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;save(Z)V"
            )
    )
    private void threadedHorizons$saveChunksAndContinue(ServerChunkCache cache, boolean flush) {
        try {
            cache.save(flush);
        } catch (ChunkSaveBarrierException failure) {
            SaveFailureSignal.recordIfBarrier(failure);
            TH_SAVE_LOG.error(
                    "Chunk save barrier failed; saving entities and remaining dimensions anyway",
                    failure);
        }
    }
}
