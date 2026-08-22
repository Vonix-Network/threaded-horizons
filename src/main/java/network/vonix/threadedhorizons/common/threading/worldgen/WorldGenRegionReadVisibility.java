package network.vonix.threadedhorizons.common.threading.worldgen;

/**
 * Vanilla WorldGenRegion cache membership is {@code firstPos}/{@code lastPos}.
 * Reduced lock radius is write/lock ownership only. A STRUCTURE_STARTS chunk
 * that is already in the region list must stay readable during NOISE.
 */
public final class WorldGenRegionReadVisibility {
    private WorldGenRegionReadVisibility() {
    }

    public static boolean vanillaHasChunk(int chunkX, int chunkZ, int firstX, int firstZ, int lastX, int lastZ) {
        return chunkX >= firstX && chunkX <= lastX && chunkZ >= firstZ && chunkZ <= lastZ;
    }

    public static boolean reducedLockOwns(int chunkX, int chunkZ, int centerX, int centerZ, int reducedRadius) {
        return chunkX >= centerX - reducedRadius
                && chunkX <= centerX + reducedRadius
                && chunkZ >= centerZ - reducedRadius
                && chunkZ <= centerZ + reducedRadius;
    }

    public static boolean readableDuringReducedLock(int chunkX, int chunkZ, int firstX, int firstZ, int lastX, int lastZ) {
        return vanillaHasChunk(chunkX, chunkZ, firstX, firstZ, lastX, lastZ);
    }
}
