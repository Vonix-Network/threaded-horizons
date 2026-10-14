package network.vonix.threadedhorizons.ci;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntitySerializationGateTest {
    @Test
    void serializationNeverRemapsBlockEntityPositionsThroughScope() throws Exception {
        String scope = Files.readString(Path.of("src/main/java/network/vonix/threadedhorizons/common/threading/chunkio/AsyncSerializationManager.java"));
        String serializer = Files.readString(Path.of("src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinChunkSerializer.java"));
        assertFalse(scope.contains("blockEntity.getBlockPos()"));
        assertFalse(scope.contains("Map<BlockPos, BlockEntity>"));
        assertFalse(serializer.contains("onChunkGetPackedBlockEntityNbt"));
        assertFalse(serializer.contains("blockEntity.saveWithId()"));
        assertTrue(serializer.contains("scope.blockEntityPositions"));
    }

    @Test
    void saveBarrierCannotTreatTimeoutAsSuccess() throws Exception {
        String save = Files.readString(Path.of("src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        assertTrue(save.contains("confirmed[0] = this.saveTracker.joinFully"));
        assertTrue(save.contains("if (!confirmed[0])"));
        assertTrue(save.contains("throw new IllegalStateException(\"Chunk save barrier did not drain\")"));
    }
}
