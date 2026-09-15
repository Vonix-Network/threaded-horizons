package network.vonix.threadedhorizons.common.optimization.worldgen.threadlocal_biome_cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeSourceCacheBoundTest {

    @Test
    void evictionKeepsCacheAtCapacityAndDropsOldest() {
        Long2ObjectLinkedOpenHashMap<String> cache = new Long2ObjectLinkedOpenHashMap<>();
        int capacity = 4;
        for (long key = 0; key < 10; key++) {
            BoundedLinkedLongCache.evictUntilCapacity(cache, capacity);
            cache.put(key, "v" + key);
            assertTrue(cache.size() <= capacity);
        }
        assertEquals(capacity, cache.size());
        assertNull(cache.get(0L), "oldest entries must be evicted");
        assertEquals("v9", cache.get(9L));
        assertEquals(1024, BoundedLinkedLongCache.BIOME_CACHE_CAPACITY);
    }
}
