package network.vonix.threadedhorizons.common.optimization.worldgen.global_biome_cache;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.mixin.ThreadedHorizonsMixinPlugin;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalBiomeCacheExclusionTest {

    @Test
    void configFieldStaysHardDisabled() {
        assertFalse(ThreadedHorizonsConfig.threadedWorldGenConfig.useGlobalBiomeCache);
    }

    @Test
    void mixinsStayOffWhileThreadLocalCacheStaysOn() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertFalse(plugin.shouldApplyMixin(
                "net.minecraft.world.level.biome.BiomeSource",
                "network.vonix.threadedhorizons.mixin.optimization.worldgen.global_biome_cache.MixinBiomeSource"));
        assertFalse(plugin.shouldApplyMixin(
                "net.minecraft.world.level.biome.MultiNoiseBiomeSource$Preset",
                "network.vonix.threadedhorizons.mixin.optimization.worldgen.global_biome_cache.MixinMultiNoiseBiomeSourcePreset"));
        assertFalse(plugin.shouldApplyMixin(
                "net.minecraft.server.level.ChunkMap",
                "network.vonix.threadedhorizons.mixin.optimization.worldgen.global_biome_cache.MixinThreadedChunkAnvilStorage"));
        assertTrue(plugin.shouldApplyMixin(
                "net.minecraft.world.level.biome.MultiNoiseBiomeSource$Preset",
                "network.vonix.threadedhorizons.mixin.optimization.worldgen.thread_local_biome_cache.MixinMultiNoiseBiomeSourcePreset"));
    }

    @Test
    void sourceDoesNotRestoreWritableConfigOrEmptyPopulateBiomesMixin() throws Exception {
        String config = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/config/ThreadedHorizonsConfig.java"));
        assertTrue(config.contains("public final boolean useGlobalBiomeCache = false;"));
        assertFalse(config.contains("this.useGlobalBiomeCache = ConfigUtils.getValue"));

        Path populateBiomes = Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/optimization/worldgen/global_biome_cache/MixinChunkGenerator.java");
        assertFalse(Files.exists(populateBiomes),
                "empty upstream populateBiomes mixin must not be restored");

        String cache = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/optimization/worldgen/global_biome_cache/MultiBiomeCache.java"));
        assertFalse(cache.contains("UnfairExecutor"),
                "unused biome-preload executor must not start threads while the family is excluded");
        assertTrue(cache.contains("SamplerIdentity"));
    }
}
