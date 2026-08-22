package network.vonix.threadedhorizons.common.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.InMemoryCommentedFormat;
import network.vonix.threadedhorizons.common.config.updater.Updaters;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigVersion4LockRadiusRestoreTest {

    @Test
    void version2StillRetiresAsyncRequestThenRestoresLockRadiusOnVersion4() {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        config.set("version", 2L);

        CommentedConfig general = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        general.set("optimizeAsyncChunkRequest", true);
        config.set("generalOptimizations", general);

        CommentedConfig worldGen = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        worldGen.set("enabled", true);
        worldGen.set("allowThreadedFeatures", true);
        worldGen.set("reduceLockRadius", true);
        config.set("threadedWorldGen", worldGen);

        Updaters.update(config);

        assertEquals(4L, config.getLongOrElse("version", 0L));
        assertEquals(Boolean.FALSE, config.get("generalOptimizations.optimizeAsyncChunkRequest"));
        assertEquals(Boolean.TRUE, config.get("threadedWorldGen.reduceLockRadius"));
        assertEquals(Boolean.TRUE, config.get("threadedWorldGen.enabled"));
        assertEquals(Boolean.TRUE, config.get("threadedWorldGen.allowThreadedFeatures"));
    }

    @Test
    void version3SafetyOffMigratesToVersion4AndReenablesLockRadius() {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        config.set("version", 3L);

        CommentedConfig general = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        general.set("optimizeAsyncChunkRequest", false);
        config.set("generalOptimizations", general);

        CommentedConfig worldGen = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        worldGen.set("reduceLockRadius", false);
        config.set("threadedWorldGen", worldGen);

        Updaters.update(config);

        assertEquals(4L, config.getLongOrElse("version", 0L));
        assertEquals(Boolean.FALSE, config.get("generalOptimizations.optimizeAsyncChunkRequest"));
        assertEquals(Boolean.TRUE, config.get("threadedWorldGen.reduceLockRadius"));
    }

    @Test
    void version4IsTerminal() {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        config.set("version", 4L);

        CommentedConfig worldGen = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        worldGen.set("reduceLockRadius", false);
        config.set("threadedWorldGen", worldGen);

        Updaters.update(config);

        assertEquals(4L, config.getLongOrElse("version", 0L));
        assertEquals(Boolean.FALSE, config.get("threadedWorldGen.reduceLockRadius"));
    }
}
