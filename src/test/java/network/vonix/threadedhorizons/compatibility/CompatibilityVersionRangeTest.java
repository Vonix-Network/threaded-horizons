package network.vonix.threadedhorizons.compatibility;

import network.vonix.threadedhorizons.compatibility.mixin.ThreadedHorizonsCompatibilityModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityVersionRangeTest {

    @Test
    void compatibilityRangeRejectsBelowAcceptsInRangeAndRejectsAbove() throws Exception {
        Method method = ThreadedHorizonsCompatibilityModule.class
                .getDeclaredMethod("isVersionInRange", String.class, String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(null, "1.9.9", "[2.0.0,3.0.0)"));
        assertTrue((Boolean) method.invoke(null, "2.5.0", "[2.0.0,3.0.0)"));
        assertFalse((Boolean) method.invoke(null, "3.0.0", "[2.0.0,3.0.0)"));
        assertTrue((Boolean) method.invoke(null, "2.7.0", ">=2.7.0"));
        assertFalse((Boolean) method.invoke(null, "2.6.9", ">=2.7.0"));
    }

    @Test
    void compatibilityRangeAcceptsForgeBuildAndPrereleaseSuffixes() throws Exception {
        Method method = ThreadedHorizonsCompatibilityModule.class
                .getDeclaredMethod("isVersionInRange", String.class, String.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(null, "2.7.0+build.184", ">=2.7.0"));
        assertTrue((Boolean) method.invoke(null, "2.8.1-beta.2", "[2.7.0,3.0.0)"));
        assertFalse((Boolean) method.invoke(null, "unknown", ">=2.7.0"));
    }
}