package network.vonix.threadedhorizons.common.fixes.worldgen.threading;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesertPyramidChestFlagsTest {

    @Test
    void copyVanillaPreservesTrueFlagsInsteadOfWipingThem() {
        AtomicReferenceArray<Boolean> atomic = new AtomicReferenceArray<>(4);
        DesertPyramidChestFlags.copyVanillaIntoAtomic(new boolean[] {true, false, true, false}, atomic);
        assertTrue(DesertPyramidChestFlags.read(atomic, 0));
        assertFalse(DesertPyramidChestFlags.read(atomic, 1));
        assertTrue(DesertPyramidChestFlags.read(atomic, 2));
        assertFalse(DesertPyramidChestFlags.read(atomic, 3));
    }

    @Test
    void writeCanStoreTrueAndFalseAndIgnoresOutOfRange() {
        AtomicReferenceArray<Boolean> atomic = new AtomicReferenceArray<>(4);
        DesertPyramidChestFlags.write(atomic, 0, true);
        assertTrue(DesertPyramidChestFlags.read(atomic, 0));
        DesertPyramidChestFlags.write(atomic, 0, false);
        assertFalse(DesertPyramidChestFlags.read(atomic, 0));
        DesertPyramidChestFlags.write(atomic, -1, true);
        DesertPyramidChestFlags.write(atomic, 4, true);
        DesertPyramidChestFlags.write(null, 0, true);
        assertFalse(DesertPyramidChestFlags.read(atomic, 1));
        assertFalse(DesertPyramidChestFlags.read(null, 0));
    }
}
