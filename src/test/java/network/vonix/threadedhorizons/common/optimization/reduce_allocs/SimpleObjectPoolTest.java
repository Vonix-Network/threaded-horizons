package network.vonix.threadedhorizons.common.optimization.reduce_allocs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleObjectPoolTest {

    @Test
    void constructorDoesNotPreallocateCapacity() {
        AtomicInteger constructed = new AtomicInteger();
        SimpleObjectPool<Object> pool = new SimpleObjectPool<>(
                unused -> {
                    constructed.incrementAndGet();
                    return new Object();
                },
                unused -> {},
                64
        );
        assertEquals(0, constructed.get());
        assertEquals(0, pool.available());
        assertEquals(64, pool.capacity());
        assertNotNull(pool.alloc());
        assertEquals(1, constructed.get());
    }

    @Test
    void releaseIsBoundedByCapacity() {
        AtomicInteger constructed = new AtomicInteger();
        SimpleObjectPool<Object> pool = new SimpleObjectPool<>(
                unused -> {
                    constructed.incrementAndGet();
                    return new Object();
                },
                unused -> {},
                2
        );
        List<Object> held = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            held.add(pool.alloc());
        }
        assertEquals(4, constructed.get());
        for (Object object : held) {
            pool.release(object);
        }
        assertEquals(2, pool.available());
        pool.alloc();
        assertEquals(1, pool.available());
        assertEquals(4, constructed.get(), "bounded reuse must not construct extra objects");
    }
}
