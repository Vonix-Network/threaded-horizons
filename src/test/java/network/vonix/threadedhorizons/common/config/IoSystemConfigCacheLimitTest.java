package network.vonix.threadedhorizons.common.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.InMemoryCommentedFormat;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void explicitOwnerTuningInsideSafeRangeIsPreserved() {
        CommentedConfig config = ioConfig(2000, 7000);
        ThreadedHorizonsConfig.IoSystemConfig io = new ThreadedHorizonsConfig.IoSystemConfig(config);
        assertEquals(2000, io.chunkDataCacheSoftLimit);
        assertEquals(7000, io.chunkDataCacheLimit);
        assertEquals(2000, ((Number) config.get("chunkDataCacheSoftLimit")).intValue());
        assertEquals(7000, ((Number) config.get("chunkDataCacheLimit")).intValue());
    }

    @Test
    void oversizedLegacyLimitsAreClampedAndWrittenBack() {
        CommentedConfig config = ioConfig(10000, 20000);
        ThreadedHorizonsConfig.IoSystemConfig io = new ThreadedHorizonsConfig.IoSystemConfig(config);
        assertEquals(4096, io.chunkDataCacheSoftLimit);
        assertEquals(8192, io.chunkDataCacheLimit);
        assertEquals(4096, ((Number) config.get("chunkDataCacheSoftLimit")).intValue());
        assertEquals(8192, ((Number) config.get("chunkDataCacheLimit")).intValue());
    }

    @Test
    void extraSoftLimitWritesHonorConfiguredSoftLimit() {
        assertEquals(0, ThreadedHorizonsConfig.IoSystemConfig.extraSoftLimitWrites(1535, 1536));
        assertEquals(1, ThreadedHorizonsConfig.IoSystemConfig.extraSoftLimitWrites(1536, 1536));
        assertEquals(1, ThreadedHorizonsConfig.IoSystemConfig.extraSoftLimitWrites(1551, 1536));
        assertEquals(2, ThreadedHorizonsConfig.IoSystemConfig.extraSoftLimitWrites(1568, 1536));
        assertEquals(0, ThreadedHorizonsConfig.IoSystemConfig.extraSoftLimitWrites(1999, 2000));
        assertEquals(1, ThreadedHorizonsConfig.IoSystemConfig.extraSoftLimitWrites(2000, 2000));
        assertFalse(ThreadedHorizonsConfig.IoSystemConfig.exceedsHardLimit(6143, 6144));
        assertTrue(ThreadedHorizonsConfig.IoSystemConfig.exceedsHardLimit(6144, 6144));
        assertTrue(ThreadedHorizonsConfig.IoSystemConfig.exceedsHardLimit(7000, 7000));
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
