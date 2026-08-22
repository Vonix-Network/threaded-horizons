package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.DesertPyramidChestFlags;
import net.minecraft.world.level.levelgen.structure.DesertPyramidPiece;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(DesertPyramidPiece.class)
public abstract class MixinDesertTempleGenerator {

    @Shadow @Final private boolean[] hasPlacedChest;

    private final AtomicReferenceArray<Boolean> hasPlacedChestAtomic = new AtomicReferenceArray<>(4);

    /**
     * Official {@code <init>(Random, int, int)} only PUTFIELDs a new
     * {@code boolean[4]} (no BASTORE). The NBT constructor BASTOREs flags, but
     * Mixin array-set intercepts are BASTORE not AASTORE and must not be the
     * sole copy path. RETURN copies the vanilla array into the atomic flags.
     */
    @Inject(method = {"<init>(Ljava/util/Random;II)V", "<init>(Lnet/minecraft/nbt/CompoundTag;)V"}, at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        DesertPyramidChestFlags.copyVanillaIntoAtomic(this.hasPlacedChest, this.hasPlacedChestAtomic);
    }

    @Dynamic
    @SuppressWarnings({"InvalidInjectorMethodSignature", "RedundantSuppression"})
    @Redirect(method = "postProcess", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/DesertPyramidPiece;hasPlacedChest:[Z", opcode = Opcodes.GETFIELD, args = {"array=set", "fuzz=14"}))
    private void redirectSetHasPlacedChest(boolean[] array, int index, boolean value) {
        DesertPyramidChestFlags.write(this.hasPlacedChestAtomic, index, value);
        if (array != null && index >= 0 && index < array.length) {
            array[index] = value;
        }
    }

    @Dynamic
    @SuppressWarnings({"InvalidInjectorMethodSignature", "RedundantSuppression"})
    @Redirect(method = {"postProcess", "addAdditionalSaveData"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/DesertPyramidPiece;hasPlacedChest:[Z", opcode = Opcodes.GETFIELD, args = "array=get"))
    private boolean redirectGetHasPlacedChest(boolean[] array, int index) {
        return DesertPyramidChestFlags.read(this.hasPlacedChestAtomic, index);
    }

    @Inject(method = "postProcess", at = @At("RETURN"))
    private void onPostProcess(CallbackInfo info) {
        DesertPyramidChestFlags.copyVanillaIntoAtomic(this.hasPlacedChest, this.hasPlacedChestAtomic);
    }

}
