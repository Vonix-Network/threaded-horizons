package network.vonix.threadedhorizons.compatibility.mixin.isleofberk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import network.vonix.threadedhorizons.compatibility.common.IsleOfBerkWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Isle of Berk 1.2.0 {@code ADragonBase.isWaterBelow} reads {@code Level.getBlockState}
 * three blocks below the entity. Require a loaded chunk and use the real block
 * state. Do not substitute {@code false} or join a main-thread chunk future.
 * ARR bytecode is not packaged; this is a runtime Mixin only.
 */
@Pseudo
@Mixin(targets = "com.GACMD.isleofberk.entity.base.dragon.ADragonBase", remap = false)
public abstract class MixinADragonBaseIsWaterBelow {

    @Inject(method = "isWaterBelow", at = @At("HEAD"), remap = false)
    private void threadedhorizons$requireLoadedWaterProbe(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        Level level = self.getLevel();
        BlockPos below = new BlockPos(self.getX(), self.getY() - 3.0D, self.getZ());
        IsleOfBerkWorldAccess.requireChunk(level, below, "isWaterBelow");
    }
}
