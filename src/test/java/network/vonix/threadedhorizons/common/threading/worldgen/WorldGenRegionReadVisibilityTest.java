package network.vonix.threadedhorizons.common.threading.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenRegionReadVisibilityTest {

    @Test
    void caseAReferencedStartAtRadiusEightStaysReadableWithReducedLockTwo() {
        int centerX = -6199;
        int centerZ = 18732;
        int firstX = centerX - 8;
        int firstZ = centerZ - 8;
        int lastX = centerX + 8;
        int lastZ = centerZ + 8;
        int reduced = 2;
        int startX = centerX + 8;
        int startZ = centerZ;

        assertTrue(WorldGenRegionReadVisibility.vanillaHasChunk(startX, startZ, firstX, firstZ, lastX, lastZ));
        assertFalse(WorldGenRegionReadVisibility.reducedLockOwns(startX, startZ, centerX, centerZ, reduced));
        assertTrue(WorldGenRegionReadVisibility.readableDuringReducedLock(
                startX, startZ, firstX, firstZ, lastX, lastZ));
    }

    @Test
    void trulyOutOfCacheChunkIsNotReadable() {
        assertFalse(WorldGenRegionReadVisibility.vanillaHasChunk(10, 0, -8, -8, 8, 8));
        assertFalse(WorldGenRegionReadVisibility.readableDuringReducedLock(10, 0, -8, -8, 8, 8));
    }
}
