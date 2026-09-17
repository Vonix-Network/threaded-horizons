package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.common.chunkio.DirtyChunkGenerations;
import network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave.MixinThreadedAnvilChunkStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class AutosaveOverflowBoundTest {

    private static final int LIMIT = 65_536;

    @Test
    void overflowSetCannotGrowPastPrimaryBound() throws Exception {
        MixinThreadedAnvilChunkStorage storage = storage();
        fillPrimary(storage);
        for (int index = 0; index < LIMIT; index++) {
            storage.enqueueDirtyChunkPosForAutoSave(new ChunkPos(index, 201));
        }
        Set<ChunkPos> overflow = overflowQueue(storage);
        Object2LongMap<ChunkPos> primary = dirtyQueue(storage);
        synchronized (primary) {
            assertEquals(LIMIT, primary.size());
            assertEquals(LIMIT, overflow.size());
        }

        ChunkPos rejected = new ChunkPos(LIMIT + 1, 201);
        storage.enqueueDirtyChunkPosForAutoSave(rejected);
        synchronized (primary) {
            assertEquals(LIMIT, overflow.size(), "overflow must reuse MAX_AUTOSAVE_QUEUE and fail closed");
            assertFalse(overflow.contains(rejected));
            assertFalse(primary.containsKey(rejected));
        }

        ChunkPos alreadyOverflowed = new ChunkPos(0, 201);
        storage.enqueueDirtyChunkPosForAutoSave(alreadyOverflowed);
        synchronized (primary) {
            assertEquals(LIMIT, overflow.size(), "re-enqueue of an overflowed pos must not grow the set");
            assertTrue(overflow.contains(alreadyOverflowed));
        }
    }

    @Test
    void rejectedEnqueueLeavesChunkDirtyAndLaterCycleSavesWhenCapacityExists() throws Exception {
        List<ChunkPos> saved = new ArrayList<>();
        MixinThreadedAnvilChunkStorage storage = savingStorage(saved);
        fillPrimary(storage);
        for (int index = 0; index < LIMIT; index++) {
            storage.enqueueDirtyChunkPosForAutoSave(new ChunkPos(index, 202));
        }

        ChunkPos rejected = new ChunkPos(LIMIT + 3, 202);
        AtomicBoolean unsaved = new AtomicBoolean(true);
        storage.enqueueDirtyChunkPosForAutoSave(rejected);
        assertTrue(unsaved.get(), "rejected overflow must not mark the chunk saved");
        assertFalse(overflowQueue(storage).contains(rejected));
        assertFalse(dirtyQueue(storage).containsKey(rejected));

        Object2LongMap<ChunkPos> primary = dirtyQueue(storage);
        synchronized (primary) {
            primary.clear();
            overflowQueue(storage).clear();
        }

        assertFalse(storage.runOneChunkAutoSave(), "a dropped overflow pos is not in either queue yet");
        assertFalse(saved.contains(rejected));
        assertTrue(unsaved.get());

        storage.enqueueDirtyChunkPosForAutoSave(rejected);
        agePrimary(storage);
        assertTrue(storage.runOneChunkAutoSave());
        assertTrue(saved.contains(rejected), "a still-dirty rejected chunk must save once capacity exists");
        unsaved.set(false);
        assertFalse(dirtyQueue(storage).containsKey(rejected));
        assertFalse(overflowQueue(storage).contains(rejected));
    }

    @Test
    void mutationAfterRejectedEnqueueIsNotClearedByOlderStore() throws Exception {
        MixinThreadedAnvilChunkStorage storage = storage();
        fillPrimary(storage);
        for (int index = 0; index < LIMIT; index++) {
            storage.enqueueDirtyChunkPosForAutoSave(new ChunkPos(index, 203));
        }

        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        long rejectedGeneration = DirtyChunkGenerations.markMutated(chunk);
        ChunkPos rejected = new ChunkPos(LIMIT + 5, 203);
        storage.enqueueDirtyChunkPosForAutoSave(rejected);
        assertTrue(unsaved.get(), "rejected overflow must not mark the chunk saved");

        long laterMutation = DirtyChunkGenerations.markMutated(chunk);
        assertTrue(laterMutation > rejectedGeneration);
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, rejectedGeneration, true),
                "an older store must not clear a mutation that happened after the rejected enqueue");
        assertTrue(unsaved.get());
        assertTrue(DirtyChunkGenerations.shouldClearUnsaved(chunk, laterMutation, true));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void primaryQueuePathStillAdmitsBelowTheBound() throws Exception {
        List<ChunkPos> saved = new ArrayList<>();
        MixinThreadedAnvilChunkStorage storage = savingStorage(saved);
        ChunkPos first = new ChunkPos(1, 204);
        ChunkPos second = new ChunkPos(2, 204);
        storage.enqueueDirtyChunkPosForAutoSave(first);
        storage.enqueueDirtyChunkPosForAutoSave(second);

        Object2LongMap<ChunkPos> primary = dirtyQueue(storage);
        synchronized (primary) {
            assertEquals(2, primary.size());
            assertTrue(primary.containsKey(first));
            assertTrue(primary.containsKey(second));
            assertTrue(overflowQueue(storage).isEmpty());
        }

        agePrimary(storage);
        assertTrue(storage.runOneChunkAutoSave());
        assertEquals(List.of(first), saved);
        synchronized (primary) {
            assertFalse(primary.containsKey(first));
            assertTrue(primary.containsKey(second));
        }
        assertTrue(storage.runOneChunkAutoSave());
        assertEquals(List.of(first, second), saved);
        synchronized (primary) {
            assertTrue(primary.isEmpty());
        }
    }

    @Test
    void closeClearsPrimaryAndOverflowQueues() throws Exception {
        MixinThreadedAnvilChunkStorage storage = storage();
        fillPrimary(storage);
        storage.enqueueDirtyChunkPosForAutoSave(new ChunkPos(LIMIT + 1, 205));
        assertEquals(LIMIT, dirtyQueue(storage).size());
        assertEquals(1, overflowQueue(storage).size());

        Method clear = MixinThreadedAnvilChunkStorage.class.getDeclaredMethod("clearAutosaveQueue", CallbackInfo.class);
        clear.setAccessible(true);
        clear.invoke(storage, new Object[]{null});

        assertTrue(dirtyQueue(storage).isEmpty());
        assertTrue(overflowQueue(storage).isEmpty());
    }

    private static MixinThreadedAnvilChunkStorage storage() {
        return new MixinThreadedAnvilChunkStorage() {
            @Override
            protected boolean saveChunkIfNeeded(ChunkHolder ignored) {
                return false;
            }
        };
    }

    private static MixinThreadedAnvilChunkStorage savingStorage(List<ChunkPos> saved) {
        return new MixinThreadedAnvilChunkStorage() {
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
    }

    private static void fillPrimary(MixinThreadedAnvilChunkStorage storage) {
        for (int index = 0; index < LIMIT; index++) {
            storage.enqueueDirtyChunkPosForAutoSave(new ChunkPos(index, 200));
        }
    }

    private static void agePrimary(MixinThreadedAnvilChunkStorage storage) throws Exception {
        Object2LongMap<ChunkPos> primary = dirtyQueue(storage);
        synchronized (primary) {
            List<ChunkPos> keys = new ArrayList<>(primary.keySet());
            for (ChunkPos pos : keys) {
                primary.put(pos, 0L);
            }
        }
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
