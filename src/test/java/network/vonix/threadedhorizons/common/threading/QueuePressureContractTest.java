package network.vonix.threadedhorizons.common.threading;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuePressureContractTest {

    @Test
    void highPressureQueuesHaveExplicitAdmissionControls() throws Exception {
        String noTick = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/notickvd/NoTickSystem.java"));
        String scheduler = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/threading/scheduler/SchedulerThread.java"));
        String storage = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/chunkio/ThreadedHorizonsStorageThread.java"));
        String saveStorage = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        String autosave = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/optimization/chunkscheduling/idle_tasks/autosave/enhanced_autosave/MixinThreadedAnvilChunkStorage.java"));
        String lighting = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/lighting/MixinThreadedAnvilChunkStorage.java"));

        assertTrue(noTick.contains("MAX_PENDING_ACTIONS") && noTick.contains("recordRejected"), noTick);
        assertTrue(scheduler.contains("MAX_RAW_TASKS") && scheduler.contains("MAX_PENDING_LOCKS")
                && scheduler.contains("MAX_PIPELINE_WAITERS"), scheduler);
        assertTrue(storage.contains("MAX_PENDING_ADMISSIONS") && storage.contains("recordFailure"), storage);
        assertTrue(saveStorage.contains("MAX_SAVE_FUTURES") && saveStorage.contains("saveTracker.tryReserve()"), saveStorage);
        assertTrue(saveStorage.contains("IllegalStateException(\"chunk save future queue is full\")"), saveStorage);
        assertTrue(autosave.contains("MAX_AUTOSAVE_QUEUE") && autosave.contains("recordRejected")
                && !autosave.contains("throw new IllegalStateException"), autosave);
        assertTrue(lighting.contains("ArrayBlockingQueue") && lighting.contains("RejectedExecutionHandler")
                && !lighting.contains("CallerRunsPolicy"), lighting);
    }
}