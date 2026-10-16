package network.vonix.threadedhorizons.mixin.threading.chunkio;

import network.vonix.threadedhorizons.common.chunkio.ChunkSaveBarrierException;
import network.vonix.threadedhorizons.common.chunkio.SaveFailureSignal;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Official {@code saveAllChunks} returns true when any level ran, then logs
 * success only when {@code flush} is true. The per-dimension line is a second
 * loop over levels, after every {@code ServerLevel.save} has returned.
 * Official {@code stopServer} pops that boolean and then closes levels, so
 * this method must still return after a chunk barrier failure. The boolean
 * becomes false when a barrier failure was recorded. A level that did not
 * fail keeps its own success line. The aggregate line stays an error.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerSaveReport {

    @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("HEAD"))
    private void threadedHorizons$beginSavePass(
            boolean suppressLog,
            boolean flush,
            boolean forced,
            CallbackInfoReturnable<Boolean> cir
    ) {
        SaveFailureSignal.beginPass();
    }

    @Redirect(
            method = "saveAllChunks(ZZZ)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V"
            )
    )
    private void threadedHorizons$withholdPerDimensionSuccess(Logger logger, String message, Object storageName) {
        ChunkSaveBarrierException failure = SaveFailureSignal.pollLoggedLevel();
        if (failure == null) {
            logger.info(message, storageName);
            return;
        }
        logger.error(
                "ThreadedAnvilChunkStorage ({}): chunk save barrier failed; success is withheld",
                storageName,
                failure);
    }

    @Redirect(
            method = "saveAllChunks(ZZZ)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;info(Ljava/lang/String;)V"
            )
    )
    private void threadedHorizons$withholdAllDimensionsSuccess(Logger logger, String message) {
        ChunkSaveBarrierException failure = SaveFailureSignal.aggregateFailure();
        if (failure == null) {
            logger.info(message);
            return;
        }
        logger.error("ThreadedAnvilChunkStorage: not all dimensions saved; chunk save barrier failed", failure);
    }

    @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("RETURN"), cancellable = true)
    private void threadedHorizons$endSavePass(
            boolean suppressLog,
            boolean flush,
            boolean forced,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!SaveFailureSignal.effectiveSaveSucceeded(cir.getReturnValueZ())) {
            cir.setReturnValue(false);
        }
        SaveFailureSignal.endPass();
    }
}
