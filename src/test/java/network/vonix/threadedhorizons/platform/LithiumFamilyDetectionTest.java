package network.vonix.threadedhorizons.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LithiumFamilyDetectionTest {

    @Test
    void lithiumCanaryOrRadiumIsTheSameAllocFamily() {
        assertTrue(LoaderHooks.isLithiumFamilyId("lithium"));
        assertTrue(LoaderHooks.isLithiumFamilyId("canary"));
        assertTrue(LoaderHooks.isLithiumFamilyId("radium"));
        assertFalse(LoaderHooks.isLithiumFamilyId("modernfix"));
        assertFalse(LoaderHooks.isLithiumFamilyId("c2me"));
        assertFalse(LoaderHooks.isLithiumFamilyId(""));
        assertFalse(LoaderHooks.isLithiumFamilyId(null));
    }
}
