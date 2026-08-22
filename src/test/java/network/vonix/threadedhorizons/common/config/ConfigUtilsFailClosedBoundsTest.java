package network.vonix.threadedhorizons.common.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.InMemoryCommentedFormat;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUtilsFailClosedBoundsTest {

    @Test
    void threadCountRejectsZeroAndNegative() {
        assertFalse(ConfigUtils.CheckType.THREAD_COUNT.check(0));
        assertFalse(ConfigUtils.CheckType.THREAD_COUNT.check(-1));
        assertTrue(ConfigUtils.CheckType.THREAD_COUNT.check(1));
        assertTrue(ConfigUtils.CheckType.THREAD_COUNT.check(8));
        assertFalse(ConfigUtils.CheckType.THREAD_COUNT.check(0x8000));
    }

    @Test
    void invalidThreadCountFallsBackToDefault() {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        config.set("threads", 0);
        ConfigUtils.ConfigScope scope = new ConfigUtils.ConfigScope(config);
        int value = ConfigUtils.getValue(scope, "threads", () -> 4, "parallelism", List.of(), null, false,
                ConfigUtils.CheckType.THREAD_COUNT);
        assertEquals(4, value);
    }

    @Test
    void integerDelayCoercesToLong() {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
        config.set("delay", 10000);
        ConfigUtils.ConfigScope scope = new ConfigUtils.ConfigScope(config);
        long value = ConfigUtils.getValue(scope, "delay", () -> 20000L, "delay", List.of(), 20000L, false,
                ConfigUtils.CheckType.POSITIVE_VALUE_ONLY);
        assertEquals(10000L, value);
        assertInstanceOf(Long.class, config.get("delay"));
    }

    @Test
    void coerceHelperConvertsNumberFamilies() {
        assertEquals(8L, ConfigUtils.coerceToExpectedType(Long.class, 8));
        assertEquals(8, ConfigUtils.coerceToExpectedType(Integer.class, 8L));
        assertEquals(Boolean.TRUE, ConfigUtils.coerceToExpectedType(Boolean.class, "true"));
        assertNull(ConfigUtils.coerceToExpectedType(Boolean.class, "yes"));
        assertNull(ConfigUtils.coerceToExpectedType(Integer.class, "nope"));
    }

    @Test
    void configWiresFailClosedNumericChecks() throws Exception {
        String config = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/config/ThreadedHorizonsConfig.java"));
        assertTrue(config.contains("CheckType.THREAD_COUNT"));
        assertTrue(config.contains("CheckType.POSITIVE_VALUE_ONLY"));
        assertTrue(config.contains("CheckType.NO_TICK_VIEW_DISTANCE"));
    }
}
