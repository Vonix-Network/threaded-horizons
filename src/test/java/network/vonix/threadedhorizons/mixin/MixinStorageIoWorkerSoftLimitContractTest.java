package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinStorageIoWorkerSoftLimitContractTest {

    @Test
    void softLimitDrainScalesWithOverflowInsteadOfClampingToZero() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/optimization/chunkio/limit_nbt_cache/MixinStorageIoWorker.java"));
        assertTrue(body.contains("extraSoftLimitWrites"));
        assertTrue(body.contains("exceedsHardLimit"));
        assertTrue(body.contains("nbtCacheDrainUntilSize"));
        assertFalse(body.contains("Math.min(1,"));
        assertFalse(body.contains("chunkDataCacheSoftLimit * 0.75"));
    }
}
