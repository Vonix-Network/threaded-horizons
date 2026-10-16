package network.vonix.threadedhorizons.common.chunkio;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One in-flight store per chunk generation.
 *
 * <p>Vanilla {@code ChunkMap.saveAllChunks(true)} calls {@code save} until it
 * returns false. The 1.1.1 overwrite left {@code unsaved} true until the
 * async store finished, so every pass admitted another future for the same
 * generation. Those duplicates filled the save-future cap, later chunks failed
 * admission, and {@code save} returned false while they were still dirty and
 * had no store queued. A repeat of a generation that is already admitted must
 * return without taking another slot. A newer generation still admits.
 */
public final class ChunkSaveAdmission {

    private static final int STRIPES = 64;
    private static final Object[] LOCKS = new Object[STRIPES];
    private static final ConcurrentHashMap<Key, Set<Long>> IN_FLIGHT = new ConcurrentHashMap<>();

    static {
        for (int index = 0; index < STRIPES; index++) {
            LOCKS[index] = new Object();
        }
    }

    private ChunkSaveAdmission() {
    }

    /**
     * @return {@code true} when the caller owns a new store for this generation
     */
    public static boolean begin(Object chunk, long generation) {
        if (chunk == null) {
            return false;
        }
        Key key = new Key(chunk);
        synchronized (lock(chunk)) {
            Set<Long> generations = IN_FLIGHT.computeIfAbsent(key, unused -> new HashSet<>());
            return generations.add(generation);
        }
    }

    public static void end(Object chunk, long generation) {
        if (chunk == null) {
            return;
        }
        Key key = new Key(chunk);
        synchronized (lock(chunk)) {
            Set<Long> generations = IN_FLIGHT.get(key);
            if (generations == null) {
                return;
            }
            generations.remove(generation);
            if (generations.isEmpty()) {
                IN_FLIGHT.remove(key, generations);
            }
        }
    }

    public static boolean isAdmitted(Object chunk, long generation) {
        if (chunk == null) {
            return false;
        }
        synchronized (lock(chunk)) {
            Set<Long> generations = IN_FLIGHT.get(new Key(chunk));
            return generations != null && generations.contains(generation);
        }
    }

    private static Object lock(Object chunk) {
        return LOCKS[System.identityHashCode(chunk) & (STRIPES - 1)];
    }

    private static final class Key {
        private final Object referent;

        private Key(Object referent) {
            this.referent = referent;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.referent == this.referent;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this.referent);
        }
    }
}
