package network.vonix.threadedhorizons.compatibility.mixin.iobvariantloader;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import network.vonix.threadedhorizons.compatibility.common.IsleOfBerkWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Variant Loader 2.7.0 {@code SurfaceRestriction.canSpawn} calls {@code canSeeSky}
 * on the FEATURES {@code WorldGenRegion}. After WorldGenRegion reads stay vanilla,
 * in-cache chunks are visible. A truly missing chunk fails closed.
 */
@Pseudo
@Mixin(targets = "nordmods.iobvariantloader.util.dragon_variant_spawner.DragonVariantSpawner$SurfaceRestriction", remap = false)
public abstract class MixinSurfaceRestriction {

    @Redirect(
            method = "canSpawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/ServerLevelAccessor;canSeeSky(Lnet/minecraft/core/BlockPos;)Z",
                    remap = true
            )
    )
    private boolean threadedhorizons$canSeeSky(ServerLevelAccessor level, BlockPos pos) {
        return IsleOfBerkWorldAccess.canSeeSky(level, pos);
    }
}
