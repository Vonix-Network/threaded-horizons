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
        assertTrue(!storage.contains("CompletableFuture.allOf"), storage);
        assertTrue(autosave.contains("dirtyChunkPosForAutoSave.clear()"), autosave);
        assertTrue(autosave.contains("overflowDirtyChunkPosForAutoSave.clear()"), autosave);
    }

    /**
     * The shutdown barrier must use the quiet-window drain, not a single
     * {@code isIdle()} sample.
     *
     * <p>A single sample can observe the gap between a completing store and the
     * follow-on store its completion schedules, so it reports "durable" with
     * writes still outstanding. That is the defect that let buffered region
     * writes land after {@code Stopping server} and lose container NBT on a
     * hard stop.
     */
    @Test
    void shutdownBarrierUsesTheQuietWindowDrainNotASingleSample() throws Exception {
        String storage = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        assertTrue(storage.contains("joinFully("),
                "the barrier must use SaveFutureTracker.joinFully");
        assertTrue(!storage.contains("saveTracker::isIdle"),
                "a single isIdle sample is not a durability barrier and must not be used");
        assertTrue(!storage.contains("managedBlock(this.saveTracker::isIdle)"),
                "the weak single-sample barrier must not be reintroduced");
    }

    /**
     * {@code flushWorker} must fence region storage, then re-confirm the
     * tracker, because a completion callback can schedule further stores.
     * The first fence is bounded {@code awaitStorageFence(synchronize(true))}.
     * {@code super.flushWorker()} is an unbounded {@code join} and must not return.
     */
    @Test
    void flushWorkerDrainsStorageThenReconfirmsTheTracker() throws Exception {
        String storage = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        int flushWorker = storage.indexOf("public void flushWorker()");
        assertTrue(flushWorker > 0, "flushWorker must exist");
        String body = storage.substring(flushWorker, storage.indexOf("\n    }", flushWorker));
        int firstJoin = body.indexOf("joinSaveFutures()");
        int firstFence = body.indexOf("SaveBarrier.awaitStorageFence");
        int secondJoin = body.indexOf("joinSaveFutures()", firstJoin + 1);
        int barrier = body.indexOf("awaitQuietAndRetryUntil");
        int secondFence = body.indexOf("SaveBarrier.awaitStorageFence", firstFence + "SaveBarrier.awaitStorageFence".length());
        int fence = body.indexOf("synchronize(true)", secondFence);
        assertTrue(firstJoin >= 0, body);
        assertTrue(!body.contains("super.flushWorker()"),
                "flushWorker must not call the unbounded ChunkStorage join: " + body);
        assertTrue(firstFence > firstJoin, "the first bounded fence must follow the first join: " + body);
        assertTrue(secondJoin > firstFence,
                "tracker must be re-confirmed after the first storage fence: " + body);
        assertTrue(barrier > secondJoin && secondFence > barrier && fence > secondFence,
                "a retry can queue a store after the first flush; synchronize(true) must follow the barrier: " + body);
    }
}