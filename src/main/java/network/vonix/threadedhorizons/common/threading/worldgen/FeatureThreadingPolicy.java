package network.vonix.threadedhorizons.common.threading.worldgen;

import net.minecraft.world.level.chunk.ChunkStatus;
import network.vonix.threadedhorizons.platform.LoaderHooks;

/**
 * FEATURES is the only status that places decorations. YUNG's Bridges
 * ({@code yungsbridges}) writes {@code setBlock} into neighbor chunks that are
 * not the current generation chunk. Parallel FEATURES on Threaded Horizons
 * workers therefore races those writes. Other statuses stay parallel.
 */
public final class FeatureThreadingPolicy {

    public static final String YUNGS_BRIDGES_MOD_ID = "yungsbridges";

    private FeatureThreadingPolicy() {
    }

    public static boolean shouldParallelizeFeatures(boolean allowThreadedFeatures, boolean yungsBridgesLoaded) {
        return allowThreadedFeatures && !yungsBridgesLoaded;
    }

    public static boolean shouldParallelizeFeatures(boolean allowThreadedFeatures) {
        return shouldParallelizeFeatures(allowThreadedFeatures, LoaderHooks.isModLoaded(YUNGS_BRIDGES_MOD_ID));
    }

    public static ChunkStatusUtils.ChunkStatusThreadingType threadingType(
            ChunkStatus status,
            boolean allowThreadedFeatures,
            boolean yungsBridgesLoaded
    ) {
        if (status.equals(ChunkStatus.STRUCTURE_STARTS)
                || status.equals(ChunkStatus.STRUCTURE_REFERENCES)
                || status.equals(ChunkStatus.BIOMES)
                || status.equals(ChunkStatus.NOISE)
                || status.equals(ChunkStatus.SURFACE)
                || status.equals(ChunkStatus.CARVERS)
                || status.equals(ChunkStatus.LIQUID_CARVERS)
                || status.equals(ChunkStatus.HEIGHTMAPS)) {
            return ChunkStatusUtils.ChunkStatusThreadingType.PARALLELIZED;
        }
        if (status.equals(ChunkStatus.SPAWN)) {
            return ChunkStatusUtils.ChunkStatusThreadingType.SINGLE_THREADED;
        }
        if (status.equals(ChunkStatus.FEATURES)) {
            return shouldParallelizeFeatures(allowThreadedFeatures, yungsBridgesLoaded)
                    ? ChunkStatusUtils.ChunkStatusThreadingType.PARALLELIZED
                    : ChunkStatusUtils.ChunkStatusThreadingType.SINGLE_THREADED;
        }
        return ChunkStatusUtils.ChunkStatusThreadingType.AS_IS;
    }
}
