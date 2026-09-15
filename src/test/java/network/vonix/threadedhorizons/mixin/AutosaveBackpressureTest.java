package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave.MixinThreadedAnvilChunkStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AutosaveBackpressureTest {

    @Test
    void autosaveQueueOverloadDoesNotThrowFromDirtyMutationAdmission() throws Exception {
        MixinThreadedAnvilChunkStorage storage = new MixinThreadedAnvilChunkStorage() {
            @Override
            protected boolean saveChunkIfNeeded(ChunkHolder ignored) {
                return false;
            }
        };
        int limit = 65_536;
        for (int index = 0; index < limit; index++) {
            storage.enqueueDirtyChunkPosForAutoSave(new ChunkPos(index, 200));
        }

        ChunkPos newest = new ChunkPos(limit + 1, 200);
        assertDoesNotThrow(() -> storage.enqueueDirtyChunkPosForAutoSave(newest));

        Object2LongMap<ChunkPos> queue = dirtyQueue(storage);
        Set<ChunkPos> overflow = overflowQueue(storage);
        synchronized (queue) {
            assertEquals(limit, queue.size());
            assertFalse(queue.containsKey(newest),
                    "an overloaded autosave queue must retain its bounded contents and keep the new dirty chunk aside");
            assertTrue(overflow.contains(newest),
                    "the rejected dirty chunk must be retained in the overflow set");
        }
    }

    @Test
    void overflowAutosaveChunksAreSavedWhenCapacityAllows() throws Exception {
        List<ChunkPos> saved = new ArrayList<>();
        MixinThreadedAnvilChunkStorage storage = new MixinThreadedAnvilChunkStorage() {
            @Override
            protected boolean saveChunkIfNeeded(ChunkHolder ignored) {
                return false;
            }

            @Override
            protected boolean saveDirtyChunkForAutosave(ChunkPos chunkPos) {
                saved.add(chunkPos);
                return true;
            }
        };

        int limit = 65_536;
        for (int index = 0; index < limit; index++) {
            storage.enqueueDirtyChunkPosForAutoSave(new ChunkPos(index, 200));
        }
        ChunkPos newest = new ChunkPos(limit + 1, 200);
        storage.enqueueDirtyChunkPosForAutoSave(newest);
        assertTrue(overflowQueue(storage).contains(newest));

        Object2LongMap<ChunkPos> queue = dirtyQueue(storage);
        synchronized (queue) {
            queue.clear();
        }

        assertTrue(storage.runOneChunkAutoSave());
        assertTrue(saved.contains(newest),
                "a previously overflowed dirty chunk must be saved once capacity exists");
        assertFalse(overflowQueue(storage).contains(newest),
                "saved overflow chunks must leave the overflow set");
        assertFalse(queue.containsKey(newest),
                "a saved overflow chunk must leave the bounded autosave queue");
    }

    @SuppressWarnings("unchecked")
    private static Object2LongMap<ChunkPos> dirtyQueue(MixinThreadedAnvilChunkStorage storage) throws Exception {
        Field field = MixinThreadedAnvilChunkStorage.class.getDeclaredField("dirtyChunkPosForAutoSave");
        field.setAccessible(true);
        return (Object2LongMap<ChunkPos>) field.get(storage);
    }

    @SuppressWarnings("unchecked")
    private static Set<ChunkPos> overflowQueue(MixinThreadedAnvilChunkStorage storage) throws Exception {
        Field field = MixinThreadedAnvilChunkStorage.class.getDeclaredField("overflowDirtyChunkPosForAutoSave");
        field.setAccessible(true);
        return (Set<ChunkPos>) field.get(storage);
    }
}
