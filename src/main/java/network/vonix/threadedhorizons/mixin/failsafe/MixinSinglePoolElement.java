package network.vonix.threadedhorizons.mixin.failsafe;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SinglePoolElement.class)
public class MixinSinglePoolElement {

    @Shadow
    @Final
    protected Holder<StructureProcessorList> processors;

    /**
     * Official 1.18.2 {@code SinglePoolElement.place} does not call
     * {@code Holder.value()}. That invoke is in {@code getSettings}, which
     * {@code place} uses to build {@code StructurePlaceSettings}.
     * A null processor list is a generation-graph failure, not an empty list.
     */
    @Redirect(method = "getSettings", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Holder;value()Ljava/lang/Object;"))
    private Object redirectProcessorValue(Holder<StructureProcessorList> holder) {
        StructureProcessorList list = holder.value();
        if (list == null) {
            throw new IllegalStateException("Null structure processor list while placing " + this.processors);
        }
        return list;
    }
}
