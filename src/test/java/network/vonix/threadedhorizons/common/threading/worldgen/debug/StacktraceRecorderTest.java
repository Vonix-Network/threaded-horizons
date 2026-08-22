package network.vonix.threadedhorizons.common.threading.worldgen.debug;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class StacktraceRecorderTest {

    @AfterEach
    void reset() {
        StacktraceRecorder.resetForTest();
    }

    @Test
    void defaultPropertiesWithReduceLockRadiusDoNotConstructThrowables() {
        assertTrue(ThreadedHorizonsConfig.threadedWorldGenConfig.reduceLockRadius,
                "required config: reduceLockRadius=true");
        assertFalse(StacktraceRecorder.isConfiguredRecordingEnabled(),
                "recording must be opt-in, not enabled by reduceLockRadius alone");

        AtomicInteger factoryCalls = new AtomicInteger();
        StacktraceRecorder.setThrowableFactoryForTest(() -> {
            factoryCalls.incrementAndGet();
            return new Throwable("should-not-run");
        });
        // Default configured path (no override): record is a no-op.
        for (int index = 0; index < 64; index++) {
            StacktraceRecorder.record();
            StacktraceRecorder.recordForcedForTest();
        }
        assertEquals(0, factoryCalls.get());
        assertEquals(0, StacktraceRecorder.throwableCreationCount());
        assertEquals(0, StacktraceRecorder.retainedTraceCount());
    }

    @Test
    void optedInRepeatedCallSiteCreatesAtMostOneThrowable() {
        StacktraceRecorder.setRecordingEnabledForTest(true);
        AtomicInteger factoryCalls = new AtomicInteger();
        StacktraceRecorder.setThrowableFactoryForTest(() -> {
            factoryCalls.incrementAndGet();
            return new Throwable("trace");
        });

        StackTraceElement[] sameSite = new StackTraceElement[]{
                new StackTraceElement("demo.HotPath", "call", "HotPath.java", 10),
                new StackTraceElement("demo.Caller", "run", "Caller.java", 20)
        };
        for (int index = 0; index < 32; index++) {
            StacktraceRecorder.recordForcedWithTraceForTest(sameSite);
        }
        assertEquals(1, factoryCalls.get());
        assertEquals(1, StacktraceRecorder.throwableCreationCount());
        assertEquals(1, StacktraceRecorder.retainedTraceCount());
    }

    @Test
    void uniqueTraceCapStopsRetentionAndThrowableCreation() {
        StacktraceRecorder.setRecordingEnabledForTest(true);
        int cap = 16;
        StacktraceRecorder.setMaxUniqueForTest(cap);
        AtomicInteger factoryCalls = new AtomicInteger();
        StacktraceRecorder.setThrowableFactoryForTest(() -> {
            factoryCalls.incrementAndGet();
            return new Throwable("unique");
        });

        for (int index = 0; index < cap + 32; index++) {
            StackTraceElement[] unique = new StackTraceElement[]{
                    new StackTraceElement("demo.Site" + index, "m", "S.java", index + 1)
            };
            StacktraceRecorder.recordForcedWithTraceForTest(unique);
        }
        assertEquals(cap, factoryCalls.get());
        assertEquals(cap, StacktraceRecorder.throwableCreationCount());
        assertEquals(cap, StacktraceRecorder.retainedTraceCount());
    }
}
