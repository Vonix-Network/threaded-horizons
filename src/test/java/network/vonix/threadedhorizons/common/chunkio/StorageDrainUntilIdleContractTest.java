package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageDrainUntilIdleContractTest {

    @Test
    void boundedDrainCannotReportFlushSuccessWithOutstandingWork() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/chunkio/ThreadedHorizonsStorageThread.java"));
        int guard = body.indexOf("if (++spins >");
        int next = body.indexOf("LockSupport.parkNanos", guard);
        String boundedSection = body.substring(guard, next);
        assertFalse(boundedSection.contains("break;"), boundedSection);
        assertTrue(boundedSection.contains("recordFailure") || boundedSection.contains("throw "), boundedSection);
    }
}