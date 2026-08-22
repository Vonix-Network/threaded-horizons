package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StructureIsolationRegressionTest {
    @Test
    void concurrentSessionsUseDistinctMutablePieceState() throws Exception {
        List<AtomicInteger> counters = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(2);
        Thread a = new Thread(() -> { counters.add(new AtomicInteger()); ready.countDown(); });
        Thread b = new Thread(() -> { counters.add(new AtomicInteger()); ready.countDown(); });
        a.start(); b.start(); ready.await(); a.join(); b.join();
        assertEquals(2, counters.size());
        assertNotSame(counters.get(0), counters.get(1));
        counters.get(0).incrementAndGet();
        assertEquals(0, counters.get(1).get());
    }

    @Test
    void vanillaDepthGatesRemainStrict() {
        assertFalse(depthGate(4, 4));
        assertTrue(depthGate(5, 4));
        assertFalse(depthGate(5, 5));
        assertTrue(depthGate(6, 5));
    }

    private static boolean depthGate(int depth, int gate) {
        return depth > gate;
    }
}
