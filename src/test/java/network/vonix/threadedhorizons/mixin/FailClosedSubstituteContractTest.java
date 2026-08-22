package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailClosedSubstituteContractTest {

    @Test
    void processorRedirectDoesNotSubstituteEmptyList() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/failsafe/MixinSinglePoolElement.java"));
        assertFalse(body.contains("ProcessorLists.EMPTY"), body);
        assertTrue(body.contains("throw "), body);
    }

    @Test
    void placedFeatureDoesNotSwallowNullOrUnrelatedState() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/fixes/worldgen/graal/MixinPlacedFeature.java"));
        assertFalse(body.contains("return false;"), body);
        assertTrue(body.contains("throw "), body);
        assertTrue(body.contains("stream has already been operated upon or closed"), body);
    }

    @Test
    void isleOfBerkHelpersDoNotSubstituteMissingWorldData() throws Exception {
        String access = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/compatibility/common/IsleOfBerkWorldAccess.java"));
        assertFalse(access.contains("missingValue"), access);
        assertFalse(access.contains("getUncachedNoiseBiome"), access);
        assertFalse(access.contains("fail-soft"), access);
        assertTrue(access.contains("throw "), access);

        String water = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/compatibility/mixin/isleofberk/MixinADragonBaseIsWaterBelow.java"));
        assertFalse(water.contains("setReturnValue(false)"), water);
        assertTrue(water.contains("throw ") || water.contains("requireChunk"), water);
    }
}
