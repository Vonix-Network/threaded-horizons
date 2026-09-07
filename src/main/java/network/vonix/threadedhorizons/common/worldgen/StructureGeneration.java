package network.vonix.threadedhorizons.common.worldgen;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Synchronizes first-time structure/stronghold generation without latching the
 * generated flag if a companion mixin throws during warmup.
 */
public final class StructureGeneration {
    private StructureGeneration() {
    }

    /**
     * ModernFix installs a stronghold-location cache whose initialization is
     * later than the ChunkMap constructor. Starting the optional asynchronous
     * warmup from that constructor can therefore observe the cache as null.
     * Keep the optimization disabled for that integration and retain vanilla
     * on-demand generation, which is the safe compatibility boundary.
     */
    public static boolean shouldUseAsyncWarmup(boolean modernFixLoaded) {
        return !modernFixLoaded;
    }

    public static void ensureGenerated(
            Object lock,
            BooleanSupplier alreadyGenerated,
            Consumer<Boolean> setGenerated,
            Runnable generate
    ) {
        if (alreadyGenerated.getAsBoolean()) {
            return;
        }
        synchronized (lock) {
            if (alreadyGenerated.getAsBoolean()) {
                return;
            }
            generate.run();
            setGenerated.accept(true);
        }
    }
}
