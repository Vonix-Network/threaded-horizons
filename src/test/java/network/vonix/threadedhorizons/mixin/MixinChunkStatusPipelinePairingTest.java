package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinChunkStatusPipelinePairingTest {

    @Test
    void generateOverwriteDoesNotStealPipelineSlots() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/worldgen/MixinChunkStatus.java"));
        assertTrue(body.contains("takeChunkHolder"));
        assertTrue(body.contains("runChunkGenWithLock"));
        assertFalse(body.contains("leaveChunkPipeline"),
                "ChunkStatusUtils already pairs acquire/leave; extra leaves steal in-progress slots");
    }
}
