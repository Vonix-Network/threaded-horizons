package network.vonix.threadedhorizons.common.optimization.worldgen.threadlocal_biome_cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/**
 * Capacity-bounded eviction for worker ThreadLocal long-keyed maps.
 * Extracted so unit tests do not load {@code BiomeSource}.
 */
public final class BoundedLinkedLongCache {

    public static final int BIOME_CACHE_CAPACITY = 1024;

    private BoundedLinkedLongCache() {
    }

    public static void evictUntilCapacity(Long2ObjectLinkedOpenHashMap<?> cache, int capacity) {
        while (cache.size() >= capacity) {
            cache.removeFirst();
        }
    }
}
