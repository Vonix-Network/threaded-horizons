package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig.GeneralOptimizationsConfig.AutoSaveConfig.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LithiumFamilyNbtMixinSkipTest {

    private static final String COMPOUND =
            "network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.MixinNbtCompound";
    private static final String COMPOUND1 =
            "network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.MixinNbtCompound1";
    private static final String TARGET = "net.minecraft.nbt.CompoundTag";
    private static final String MID_TICK =
            "network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.mid_tick_chunk_tasks.MixinServerChunkManager";

    @Test
    void nbtRedirectMixinsApplyWithoutLithiumFamilyMods() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertTrue(plugin.evaluate(TARGET, COMPOUND, true, Mode.ENHANCED, false));
        assertTrue(plugin.evaluate(TARGET, COMPOUND1, true, Mode.ENHANCED, false));
    }

    @Test
    void nbtRedirectMixinsSkipWhenCanaryIsPresent() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertFalse(plugin.evaluate(TARGET, COMPOUND, true, Mode.ENHANCED, true),
                "Lithium-family mods own the CompoundTag HashMap redirect; TH must yield");
        assertFalse(plugin.evaluate(TARGET, COMPOUND1, true, Mode.ENHANCED, true));
    }

    @Test
    void midTickChunkTasksSkipWhenLithiumFamilyOwnsBlockingChunkAccess() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertTrue(plugin.evaluate("net.minecraft.server.level.ServerChunkCache", MID_TICK,
                true, Mode.ENHANCED, false));
        assertFalse(plugin.evaluate("net.minecraft.server.level.ServerChunkCache", MID_TICK,
                true, Mode.ENHANCED, true),
                "Lithium-family getChunkOffThread must not be polled by TH mid-tick tasks");
    }
}
