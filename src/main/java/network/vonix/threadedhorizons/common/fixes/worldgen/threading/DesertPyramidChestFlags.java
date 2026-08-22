package network.vonix.threadedhorizons.common.fixes.worldgen.threading;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Identity for {@code DesertPyramidPiece.hasPlacedChest}. Mixin array-element
 * intercepts on {@code boolean[]} are BASTORE/BALOAD, not AASTORE, and the
 * random constructor never stores elements. Constructor RETURN copies the
 * vanilla array so NBT flags cannot be wiped to false.
 */
public final class DesertPyramidChestFlags {
    private DesertPyramidChestFlags() {
    }

    public static void copyVanillaIntoAtomic(boolean[] vanilla, AtomicReferenceArray<Boolean> atomic) {
        if (vanilla == null || atomic == null) {
            return;
        }
        int length = Math.min(vanilla.length, atomic.length());
        for (int index = 0; index < length; index++) {
            atomic.set(index, vanilla[index]);
        }
    }

    public static boolean read(AtomicReferenceArray<Boolean> atomic, int index) {
        if (atomic == null || index < 0 || index >= atomic.length()) {
            return false;
        }
        Boolean value = atomic.get(index);
        return value != null && value;
    }

    public static void write(AtomicReferenceArray<Boolean> atomic, int index, boolean value) {
        if (atomic == null || index < 0 || index >= atomic.length()) {
            return;
        }
        Boolean boxed = value ? Boolean.TRUE : Boolean.FALSE;
        Boolean current = atomic.get(index);
        if (current == boxed) {
            return;
        }
        if (!atomic.compareAndSet(index, current, boxed)) {
            atomic.set(index, boxed);
        }
    }
}
