package network.vonix.threadedhorizons.common.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerThreadLocalsTest {

    @Test
    void clearCurrentThreadDropsManagedValues() {
        ThreadLocal<AtomicInteger> local = WorkerThreadLocals.create(() -> new AtomicInteger(7));
        AtomicInteger first = local.get();
        assertEquals(7, first.get());
        first.set(99);
        assertEquals(99, local.get().get());
        WorkerThreadLocals.clearCurrentThread();
        AtomicInteger second = local.get();
        assertNotSame(first, second);
        assertEquals(7, second.get(), "remove must drop the mutated instance");
        local.remove();
    }

    @Test
    void managedLocalsAreRegistered() {
        int before = WorkerThreadLocals.managedCount();
        ThreadLocal<String> local = WorkerThreadLocals.create(() -> "held");
        assertEquals("held", local.get());
        assertTrue(WorkerThreadLocals.managedCount() >= before + 1);
        local.remove();
    }
}
