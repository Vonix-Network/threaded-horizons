package network.vonix.threadedhorizons.common.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.InMemoryCommentedFormat;
import network.vonix.threadedhorizons.common.config.updater.Updaters;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigVersion5IoCacheMigrationTest {

    @Test
    void version4OversizedCacheLimitsAreClampedAndExplicitSafeTuningIsKept() {
        CommentedConfig oversized = version4Io(10000, 20000);
        Updaters.update(oversized);
        assertEquals(5L, oversized.getLongOrElse("version", 0L));
        assertEquals(4096, ((Number) oversized.get("ioSystem.chunkDataCacheSoftLimit")).intValue());
        assertEquals(8192, ((Number) oversized.get("ioSystem.chunkDataCacheLimit")).intValue());

        CommentedConfig ownerTuned = version4Io(2000, 7000);
        Updaters.update(ownerTuned);
        assertEquals(5L, ownerTuned.getLongOrElse("version", 0L));
        assertEquals(2000, ((Number) ownerTuned.get("ioSystem.chunkDataCacheSoftLimit")).intValue());
        assertEquals(7000, ((Number) ownerTuned.get("ioSystem.chunkDataCacheLimit")).intValue());
    }

    @Test
    void version4MissingIoKeysAreLeftForRuntimeDefaults() {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        config.set("version", 4L);
        Updaters.update(config);
        assertEquals(5L, config.getLongOrElse("version", 0L));
        assertNull(config.get("ioSystem"));
    }

    private static CommentedConfig version4Io(int softLimit, int hardLimit) {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        config.set("version", 4L);
        CommentedConfig io = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        io.set("chunkDataCacheSoftLimit", softLimit);
        io.set("chunkDataCacheLimit", hardLimit);
        config.set("ioSystem", io);
        return config;
    }
}
