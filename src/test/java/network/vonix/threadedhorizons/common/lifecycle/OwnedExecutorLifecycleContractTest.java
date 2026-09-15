package network.vonix.threadedhorizons.common.lifecycle;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedExecutorLifecycleContractTest {

    @Test
    void ownedLightingAndWorkerExecutorsAreAwaitedDuringClose() throws Exception {
        String lighting = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/lighting/MixinThreadedAnvilChunkStorage.java"));
        String worker = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinStorageIoWorker.java"));
        String storage = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/chunkio/ThreadedHorizonsStorageVanillaInterface.java"));
        assertTrue(lighting.contains("awaitTermination"), lighting);
        assertTrue(worker.contains("isTerminated"), worker);
        assertTrue(storage.contains("backend.close().join()"), storage);
    }

    @Test
    void closeClearsQueuedSaveAndAutosaveReferences() throws Exception {
        String storage = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        String autosave = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/optimization/chunkscheduling/idle_tasks/autosave/enhanced_autosave/MixinThreadedAnvilChunkStorage.java"));
        assertTrue(storage.contains("joinSaveFutures()"), storage);
        assertTrue(storage.contains("saveTracker::isIdle"), storage);
        assertTrue(!storage.contains("CompletableFuture.allOf"), storage);
        assertTrue(autosave.contains("dirtyChunkPosForAutoSave.clear()"), autosave);
        assertTrue(autosave.contains("overflowDirtyChunkPosForAutoSave.clear()"), autosave);
    }
}