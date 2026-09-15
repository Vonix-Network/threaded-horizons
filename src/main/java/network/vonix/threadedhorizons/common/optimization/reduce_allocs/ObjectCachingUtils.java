package network.vonix.threadedhorizons.common.optimization.reduce_allocs;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import network.vonix.threadedhorizons.common.lifecycle.WorkerThreadLocals;

import java.util.BitSet;
import java.util.function.IntFunction;

public class ObjectCachingUtils {

    public static final int BITSET_CACHE_CAPACITY = 16;

    private static final IntFunction<BitSet> bitSetConstructor = BitSet::new;

    public static final ThreadLocal<Int2ObjectLinkedOpenHashMap<BitSet>> BITSETS = WorkerThreadLocals.create(Int2ObjectLinkedOpenHashMap::new);

    private ObjectCachingUtils() {
    }

    public static BitSet getCachedOrNewBitSet(int bits) {
        final Int2ObjectLinkedOpenHashMap<BitSet> cache = BITSETS.get();
        BitSet bitSet = cache.getAndMoveToLast(bits);
        if (bitSet == null) {
            bitSet = bitSetConstructor.apply(bits);
            cache.put(bits, bitSet);
            while (cache.size() > BITSET_CACHE_CAPACITY) {
                cache.removeFirst();
            }
        }
        bitSet.clear();
        return bitSet;
    }

}
