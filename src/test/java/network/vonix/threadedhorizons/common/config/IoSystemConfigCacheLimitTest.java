package network.vonix.threadedhorizons.common.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.InMemoryCommentedFormat;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoSystemConfigCacheLimitTest {

    @Test
    void invertedHardLimitIsRaisedToSoftLimit() {
        CommentedConfig config = ioConfig(1536, 100);
        ThreadedHorizonsConfig.IoSystemConfig io = new ThreadedHorizonsConfig.IoSystemConfig(config);
        assertEquals(1536, io.chunkDataCacheSoftLimit);
        assertTrue(io.chunkDataCacheLimit >= io.chunkDataCacheSoftLimit);
        assertEquals(1536, io.chunkDataCacheLimit);
        assertEquals(1536, ((Number) config.get("chunkDataCacheLimit")).intValue());
    }

    @Test
    void validCacheLimitsAreUnchanged() {
        CommentedConfig config = ioConfig(1536, 6144);
        ThreadedHorizonsConfig.IoSystemConfig io = new ThreadedHorizonsConfig.IoSystemConfig(config);
        assertEquals(1536, io.chunkDataCacheSoftLimit);
        assertEquals(6144, io.chunkDataCacheLimit);
        assertEquals(6144, ((Number) config.get("chunkDataCacheLimit")).intValue());
    }

    @Test
    void nbtCacheDrainUntilSizeRespectsHardCapAndNeverReturnsZero() {
        int inverted = ThreadedHorizonsConfig.IoSystemConfig.nbtCacheDrainUntilSize(1536, 100);
        assertTrue(inverted <= 100);
        assertTrue(inverted >= 1);

        int hardBelowSoftDrain = ThreadedHorizonsConfig.IoSystemConfig.nbtCacheDrainUntilSize(1536, 1000);
        assertTrue(hardBelowSoftDrain <= 1000);
        assertEquals(1000, hardBelowSoftDrain);

        int normal = ThreadedHorizonsConfig.IoSystemConfig.nbtCacheDrainUntilSize(1536, 6144);
        assertEquals(Math.min(6144, (int) (1536 * 0.75)), normal);

        assertEquals(1, ThreadedHorizonsConfig.IoSystemConfig.nbtCacheDrainUntilSize(0, 0));
        assertEquals(1, ThreadedHorizonsConfig.IoSystemConfig.nbtCacheDrainUntilSize(1, 1));
        assertTrue(ThreadedHorizonsConfig.IoSystemConfig.nbtCacheDrainUntilSize(-5, -8) >= 1);
    }

    private static CommentedConfig ioConfig(int softLimit, int hardLimit) {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        config.set("chunkDataCacheSoftLimit", softLimit);
        config.set("chunkDataCacheLimit", hardLimit);
        return config;
    }
}
