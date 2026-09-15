package network.vonix.threadedhorizons.common.threading.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerLockAdmissionCeilingTest {

    @Test
    void lockAdmissionCeilingFollowsPipelineInsteadOfAbsoluteCap() {
        assertEquals(256, SchedulerThread.lockAdmissionCeiling(1));
        assertEquals(256, SchedulerThread.lockAdmissionCeiling(8));
        assertEquals(1024, SchedulerThread.lockAdmissionCeiling(64));
        assertEquals(1024, SchedulerThread.lockAdmissionCeiling(SchedulerThread.computePipelineLimit(8, 32L * 1024 * 1024 * 1024)));
        assertTrue(SchedulerThread.lockAdmissionCeiling(64) < SchedulerThread.MAX_PENDING_LOCKS);
        assertEquals(SchedulerThread.MAX_PENDING_LOCKS, SchedulerThread.lockAdmissionCeiling(1_000_000));
    }
}
