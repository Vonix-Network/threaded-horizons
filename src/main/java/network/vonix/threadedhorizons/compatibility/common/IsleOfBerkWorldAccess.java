package network.vonix.threadedhorizons.compatibility.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;

/**
 * Owned Isle of Berk / Variant Loader world reads during threaded FEATURES.
 * Missing chunks fail closed. Do not substitute sky, biome, or block data.
 */
public final class IsleOfBerkWorldAccess {

    private IsleOfBerkWorldAccess() {
    }

    public static boolean hasChunkAt(LevelReader level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        return level.hasChunk(chunkX, chunkZ);
    }

    public static void requireChunk(LevelReader level, BlockPos pos, String probe) {
        if (!hasChunkAt(level, pos)) {
            throw new IllegalStateException(
                    "Threaded Horizons: " + probe + " required a loaded chunk at " + pos
                            + " on " + (level == null ? "null" : level.getClass().getName()));
        }
    }

    public static boolean canSeeSky(LevelReader level, BlockPos pos) {
        requireChunk(level, pos, "canSeeSky");
        return level.canSeeSky(pos);
    }

    public static Holder<Biome> biome(LevelReader level, BlockPos pos) {
        requireChunk(level, pos, "getBiome");
        return level.getBiome(pos);
    }
}
