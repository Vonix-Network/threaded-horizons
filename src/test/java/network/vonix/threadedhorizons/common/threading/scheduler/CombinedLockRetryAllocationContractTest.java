package network.vonix.threadedhorizons.common.threading.scheduler;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombinedLockRetryAllocationContractTest {

    @Test
    void tryAcquireReusesTokenArrayAndDoesNotAllocateLockEntries() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/threading/scheduler/SchedulingAsyncCombinedLock.java"));
        assertTrue(body.contains("private final AsyncLock.LockToken[] acquiredTokens"), body);
        assertFalse(body.contains("record LockEntry"), body);
        assertFalse(body.contains("new LockEntry["), body);
        String tryAcquire = body.substring(body.indexOf("public synchronized boolean tryAcquire()"));
        assertFalse(tryAcquire.contains("new LockEntry"), tryAcquire);
        assertFalse(tryAcquire.contains("new AsyncLock.LockToken["), tryAcquire);
    }

    @Test
    void chunkStatusUtilsDoesNotStageLockNamesThroughHashSet() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/threading/worldgen/ChunkStatusUtils.java"));
        assertTrue(body.contains("ChunkPos[] fetchedLocks"), body);
        assertFalse(body.contains("new HashSet<>(fetchedLocks)"), body);
        assertFalse(body.contains("new ArrayList<>((2 * radius + 1)"), body);
    }
}
