package network.vonix.threadedhorizons.compatibility.mixin.iobvariantloader;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import network.vonix.threadedhorizons.compatibility.common.IsleOfBerkWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Variant Loader 2.7.0 {@code isVariantIn} reads {@code getBiome} during FEATURES
 * spawn filtering. In-cache chunks stay visible. A missing chunk fails closed
 * instead of substituting an uncached noise biome.
 */
@Pseudo
@Mixin(targets = "nordmods.iobvariantloader.util.dragon_variant_spawner.DragonVariantSpawnerUtil", remap = false)
public abstract class MixinDragonVariantSpawnerUtil {

    @Redirect(
            method = "isVariantIn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/ServerLevelAccessor;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;",
                    remap = true
            )
    )
    private static Holder<Biome> threadedhorizons$biome(ServerLevelAccessor level, BlockPos pos) {
        return IsleOfBerkWorldAccess.biome(level, pos);
    }
}
