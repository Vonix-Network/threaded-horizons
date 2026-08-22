package network.vonix.threadedhorizons.mixin.fixes.worldgen.graal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

@Mixin(PlacedFeature.class)
public class MixinPlacedFeature {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GRAAL_STREAM_CLOSED = "stream has already been operated upon or closed";

    @Shadow @Final private List<PlacementModifier> placement;

    @Shadow @Final private Holder<ConfiguredFeature<?, ?>> feature;

    /**
     * @author ishland
     * @reason retry only the known Graal stream-closed failure
     */
    @Overwrite
    private boolean placeWithContext(PlacementContext context, Random random, BlockPos pos) {
        Stream<BlockPos> stream;
        for (int retries = 1; ; retries++) {
            try {
                stream = Stream.of(pos);

                for (PlacementModifier placementModifier : this.placement) {
                    stream = stream.flatMap(posx -> placementModifier.getPositions(context, random, posx));
                }

                break;
            } catch (IllegalStateException e) {
                if (!GRAAL_STREAM_CLOSED.equals(e.getMessage())) {
                    throw e;
                }
                if (retries == 3) {
                    throw e;
                }
                LOGGER.warn("Possible graalvm issue, retrying... (attempt {})", retries + 1, e);
            }
        }

        ConfiguredFeature<?, ?> configuredFeature = this.feature.value();
        if (configuredFeature == null) {
            throw new IllegalStateException("NULL configuredFeature in PlacedFeature " + this);
        }
        MutableBoolean placed = new MutableBoolean();
        stream.forEach(blockPos -> {
            if (configuredFeature.place(context.getLevel(), context.generator(), random, blockPos)) {
                placed.setTrue();
            }
        });
        return placed.isTrue();
    }

}
