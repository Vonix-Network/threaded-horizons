package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.GlobalExecutors;
import network.vonix.threadedhorizons.common.worldgen.StructureGeneration;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage {

    @Shadow
    private ChunkGenerator generator;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        if (!StructureGeneration.shouldUseAsyncWarmup(LoaderHooks.isModLoaded("modernfix"))) {
            org.slf4j.LoggerFactory.getLogger("Threaded Horizons").info(
                    "Skipping async structure warmup while ModernFix is loaded; generation remains on-demand"
            );
            return;
        }
        GlobalExecutors.executor.execute(() -> {
            try {
                this.generator.ensureStructuresGenerated();
            } catch (Throwable error) {
                org.slf4j.LoggerFactory.getLogger("Threaded Horizons").warn(
                        "Async structure warmup did not complete; generation will retry on demand",
                        error
                );
            }
        });
    }

}
