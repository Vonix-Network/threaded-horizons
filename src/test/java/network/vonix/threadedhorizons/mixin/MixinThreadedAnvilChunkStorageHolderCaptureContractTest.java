package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinThreadedAnvilChunkStorageHolderCaptureContractTest {

    @Test
    void holderCaptureTargetsOfficialAndProductionSrgLambdas() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/worldgen/MixinThreadedAnvilChunkStorage.java"));
        assertTrue(body.contains("lambda$scheduleChunkGeneration$22"));
        assertTrue(body.contains("m_203017_"));
        assertTrue(body.contains("CrashReport;forThrowable"));
        assertTrue(body.contains("CrashReport;m_127521_"));
        assertTrue(body.contains("private CrashReport clearWorldGenHolderOnExceptionSrg"));
        assertTrue(body.contains("@Redirect"));
        assertFalse(body.contains("@Group(name = \"thClearWorldGenHolderOnException\""));
        assertTrue(body.contains("thCaptureWorldGenHolder"));
        assertTrue(body.contains("setChunkHolder"));
        assertFalse(body.contains("leaveChunkPipeline"));
        assertFalse(body.contains("import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;"));
    }
}
