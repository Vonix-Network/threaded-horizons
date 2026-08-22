package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinChunkRegionReadContractTest {

    @Test
    void worldGenRegionReadsStayVanillaAndDoNotCancel() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/worldgen/MixinChunkRegion.java"));
        assertTrue(body.contains("StacktraceRecorder.record()"));
        assertFalse(body.contains("cancellable = true"), body);
        assertFalse(body.contains("cancellable=true"), body);
        assertFalse(body.contains("setReturnValue"), body);
        assertFalse(body.contains("cir.setReturnValue(null)"), body);
        assertFalse(body.contains("cir.setReturnValue(false)"), body);
    }
}
