package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(StructureStart.class)
public class MixinStructureStart {

    private final AtomicInteger referencesAtomic = new AtomicInteger();

    @Dynamic
    @Redirect(method = {"createTag", "isValid", "canBeReferenced", "getReferences"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StructureStart;references:I", opcode = Opcodes.GETFIELD))
    private int redirectGetReferences(StructureStart structureStart) {
        return referencesAtomic.get();
    }

    /**
     * @author ishland
     * @reason atomic operation
     */
    @Overwrite
    public void addReference() {
        this.referencesAtomic.incrementAndGet();
    }

    @Redirect(method = "<init>(Lnet/minecraft/world/level/levelgen/feature/ConfiguredStructureFeature;Lnet/minecraft/world/level/ChunkPos;ILnet/minecraft/world/level/levelgen/structure/pieces/PiecesContainer;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StructureStart;references:I", opcode = Opcodes.PUTFIELD))
    private void redirectInitialReferences(StructureStart structureStart, int value) {
        this.referencesAtomic.set(value);
    }

}
