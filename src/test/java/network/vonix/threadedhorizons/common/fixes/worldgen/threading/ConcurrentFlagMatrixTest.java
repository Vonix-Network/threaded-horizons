package network.vonix.threadedhorizons.common.fixes.worldgen.threading;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentFlagMatrixTest {

    @Test
    void setifIsAtomicCompareAndSet() {
        ConcurrentFlagMatrix matrix = new ConcurrentFlagMatrix(4, 4, 0);
        matrix.set(1, 2, 7);
        matrix.setif(1, 2, 7, 9);
        assertEquals(9, matrix.get(1, 2));
        matrix.setif(1, 2, 7, 3);
        assertEquals(9, matrix.get(1, 2));
    }

    @Test
    void contendedSetifLeavesASingleWinnerValue() throws Exception {
        ConcurrentFlagMatrix matrix = new ConcurrentFlagMatrix(2, 2, 0);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int index = 0; index < threads; index++) {
            int value = index + 1;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    matrix.setif(0, 0, 0, value);
                } catch (Exception exception) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, failures.get());
        int winner = matrix.get(0, 0);
        assertTrue(winner >= 1 && winner <= threads, "winner=" + winner);
    }

    @Test
    void setifHoldsWriteLockForTheCompare() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/fixes/worldgen/threading/ConcurrentFlagMatrix.java"));
        int setifAt = body.indexOf("public void setif");
        assertTrue(setifAt >= 0);
        String setif = body.substring(setifAt, body.indexOf("public boolean edgesTo"));
        assertTrue(setif.contains("writeLock()"));
        assertTrue(setif.contains("super.get"));
        assertTrue(setif.contains("super.set"));
    }
}
