package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementExclusionContractTest {

    @Test
    void asyncChunkRequestStaysRetiredAndDoesNotJoinMainThreadProcessor() throws Exception {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertFalse(plugin.shouldApplyMixin(
                "net.minecraft.server.level.ServerChunkCache",
                "network.vonix.threadedhorizons.mixin.optimization.chunkaccess.async_chunk_request.MixinServerChunkManager"));
        assertFalse(ThreadedHorizonsConfig.generalOptimizationsConfig.optimizeAsyncChunkRequest);

        String mixin = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/optimization/chunkaccess/async_chunk_request/MixinServerChunkManager.java"));
        assertTrue(mixin.contains("CFUtil.join"));
        assertTrue(mixin.contains("mainThreadProcessor"));

        String pluginSource = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/ThreadedHorizonsMixinPlugin.java"));
        assertTrue(pluginSource.contains("optimization.chunkaccess.async_chunk_request."));
        assertTrue(pluginSource.contains("return false;"));
    }

    @Test
    void vectorAccelerationSourceTreesAreAbsent() {
        assertFalse(Files.exists(Path.of("src/main/java/network/vonix/threadedhorizons/libs/vectorized_algorithms")));
        assertFalse(Files.exists(Path.of("src/main/java/network/vonix/threadedhorizons/mixin/optimization/vectorizations")));
        assertFalse(Files.exists(Path.of("src/main/java/network/vonix/threadedhorizons/mixin/optimization/vectorizations/vectorize_noise/MixinPerlinNoiseSampler.java")));
    }

    @Test
    void fabricCompanionMixinsAreAbsent() {
        Path compat = Path.of("src/main/java/network/vonix/threadedhorizons/compatibility/mixin");
        assertFalse(Files.exists(compat.resolve("betterend")));
        assertFalse(Files.exists(compat.resolve("betternether")));
        assertFalse(Files.exists(compat.resolve("charm")));
        assertFalse(Files.exists(compat.resolve("terra")));
        assertFalse(Files.exists(compat.resolve("thebumblezone")));
        assertTrue(Files.exists(compat.resolve("isleofberk")));
        assertTrue(Files.exists(compat.resolve("iobvariantloader")));
    }
}
