package network.vonix.threadedhorizons.mixin.threading.chunkio;

import network.vonix.threadedhorizons.common.chunkio.DirtyChunkGenerations;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Official 1.18.2 {@code ChunkAccess} structure-start and structure-reference
 * methods write {@code unsaved} with {@code putfield} and do not call
 * {@code setUnsaved}. Advance the dirty generation before that write.
 */
@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccessStructureGenerations {

    @Inject(
            method = {
                    "setStartForFeature(Lnet/minecraft/world/level/levelgen/feature/ConfiguredStructureFeature;Lnet/minecraft/world/level/levelgen/structure/StructureStart;)V",
                    "setAllStarts(Ljava/util/Map;)V",
                    "addReferenceForFeature(Lnet/minecraft/world/level/levelgen/feature/ConfiguredStructureFeature;J)V",
                    "setAllReferences(Ljava/util/Map;)V"
            },
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;unsaved:Z",
                    opcode = Opcodes.PUTFIELD
            )
    )
    private void onStructureUnsavedAdvanceGeneration(CallbackInfo ci) {
        DirtyChunkGenerations.markMutated((ChunkAccess) (Object) this);
    }
}
