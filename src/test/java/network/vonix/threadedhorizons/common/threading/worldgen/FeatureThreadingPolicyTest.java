package network.vonix.threadedhorizons.common.threading.worldgen;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureThreadingPolicyTest {

    @Test
    void yungsBridgesForcesSingleThreadedFeaturesOnly() {
        assertTrue(FeatureThreadingPolicy.shouldParallelizeFeatures(true, false));
        assertFalse(FeatureThreadingPolicy.shouldParallelizeFeatures(true, true));
        assertFalse(FeatureThreadingPolicy.shouldParallelizeFeatures(false, false));
        assertFalse(FeatureThreadingPolicy.shouldParallelizeFeatures(false, true));
    }

    @Test
    void detectionUsesYungsBridgesModId() {
        assertEquals("yungsbridges", FeatureThreadingPolicy.YUNGS_BRIDGES_MOD_ID);
    }

    @Test
    void policyIsWiredOnlyToFeaturesAndLeavesOtherStatusesParallel() throws Exception {
        String policy = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/threading/worldgen/FeatureThreadingPolicy.java"));
        assertTrue(policy.contains("ChunkStatus.FEATURES"));
        assertTrue(policy.contains("shouldParallelizeFeatures(allowThreadedFeatures, yungsBridgesLoaded)"));
        assertTrue(policy.contains("ChunkStatus.NOISE"));
        assertTrue(policy.contains("PARALLELIZED"));
        assertFalse(policy.contains("ChunkStatus.NOISE") && policy.contains("yungsBridgesLoaded ? SINGLE_THREADED"));

        String utils = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/threading/worldgen/ChunkStatusUtils.java"));
        assertTrue(utils.contains("FeatureThreadingPolicy.threadingType"));
        assertTrue(utils.contains("FeatureThreadingPolicy.YUNGS_BRIDGES_MOD_ID"));
        assertFalse(utils.contains("allowThreadedFeatures ? PARALLELIZED"));
    }
}
