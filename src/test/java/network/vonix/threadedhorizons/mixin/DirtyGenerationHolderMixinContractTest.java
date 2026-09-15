package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DirtyGenerationHolderMixinContractTest {

    @Test
    void chunkAccessMixinImplementsPerChunkHolder() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinChunkDirtyGenerations.java"));
        assertTrue(body.contains("implements DirtyGenerationHolder"), body);
        assertTrue(body.contains("AtomicLong threadedHorizons$generation"), body);
        assertTrue(body.contains("Object threadedHorizons$generationLock"), body);
        assertTrue(body.contains("DirtyChunkGenerations.markMutated"), body);
    }
}
