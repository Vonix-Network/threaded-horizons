package network.vonix.threadedhorizons.common.optimization.reduce_allocs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectCachingUtilsBoundTest {

    @AfterEach
    void clearThreadLocal() {
        ObjectCachingUtils.BITSETS.remove();
    }

    @Test
    void bitSetCacheNeverExceedsCapacity() {
        for (int bits = 1; bits <= ObjectCachingUtils.BITSET_CACHE_CAPACITY + 8; bits++) {
            assertNotNull(ObjectCachingUtils.getCachedOrNewBitSet(bits));
            assertTrue(ObjectCachingUtils.BITSETS.get().size() <= ObjectCachingUtils.BITSET_CACHE_CAPACITY);
        }
        assertEquals(ObjectCachingUtils.BITSET_CACHE_CAPACITY, ObjectCachingUtils.BITSETS.get().size());
    }

    @Test
    void reusedBitSetIsCleared() {
        var first = ObjectCachingUtils.getCachedOrNewBitSet(32);
        first.set(3);
        var second = ObjectCachingUtils.getCachedOrNewBitSet(32);
        assertSame(first, second);
        assertTrue(second.isEmpty());
    }
}
