package network.vonix.threadedhorizons.mixin.threading;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReducedTaskRadiusLogGateTest {

    @Test
    void taskRadiusPrintfIsBehindADebugProperty() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/worldgen/MixinChunkStatus.java"));
        assertTrue(body.contains("network.vonix.threadedhorizons.debug.logTaskRadius"), body);
        assertTrue(body.contains("System.out.printf"), body);
        int printf = body.indexOf("System.out.printf");
        int gate = body.lastIndexOf("Boolean.getBoolean", printf);
        assertTrue(gate >= 0 && gate < printf, body);
    }
}
