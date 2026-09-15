package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig.GeneralOptimizationsConfig.AutoSaveConfig.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncChunkRequestMixinRetiredTest {

    private static final String TARGET = "net.minecraft.server.level.ServerChunkCache";
    private static final String ASYNC_REQUEST =
            "network.vonix.threadedhorizons.mixin.optimization.chunkaccess.async_chunk_request.MixinServerChunkManager";
    @Test
    void asyncChunkRequestMixinStaysOffRegardlessOfLegacyConfigIntent() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertFalse(plugin.evaluate(TARGET, ASYNC_REQUEST, true, Mode.ENHANCED, false));
        assertFalse(plugin.evaluate(TARGET, ASYNC_REQUEST, true, Mode.ENHANCED, true));
        assertFalse(plugin.evaluate(TARGET, ASYNC_REQUEST, false, Mode.VANILLA, false));
    }

    @Test
    void liveConfigDefaultsRetireAsyncRequestAndKeepThreadedWorldgen() {
        assertFalse(ThreadedHorizonsConfig.generalOptimizationsConfig.optimizeAsyncChunkRequest);
        assertTrue(ThreadedHorizonsConfig.threadedWorldGenConfig.allowThreadedFeatures);
        assertTrue(network.vonix.threadedhorizons.common.threading.worldgen.FeatureThreadingPolicy
                .shouldParallelizeFeatures(ThreadedHorizonsConfig.threadedWorldGenConfig.allowThreadedFeatures, false));
        assertFalse(network.vonix.threadedhorizons.common.threading.worldgen.FeatureThreadingPolicy
                .shouldParallelizeFeatures(ThreadedHorizonsConfig.threadedWorldGenConfig.allowThreadedFeatures, true));
    }

    @Test
    void pluginHardSkipDoesNotReadOptimizeAsyncChunkRequestFlag() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertFalse(plugin.shouldApplyMixin(TARGET, ASYNC_REQUEST),
                "live plugin path must ignore the retired config key");
    }
}
