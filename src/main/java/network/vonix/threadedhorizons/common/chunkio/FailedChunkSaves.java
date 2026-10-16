package network.vonix.threadedhorizons.common.chunkio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Chunks whose store was attempted and failed, owned by one chunk map.
 * Identity keys, so two chunks that compare equal by position stay distinct.
 * A second dimension has its own instance and cannot drain or retry these
 * entries. Entries are strong until {@link #drain()} or {@link #clear(Object)};
 * the owning tick must drain so a chunk is not pinned until shutdown.
 */
public final class FailedChunkSaves {

    private final Object lock = new Object();
    private final Set<Object> pending = Collections.newSetFromMap(new IdentityHashMap<>());

    public void record(Object chunk) {
        if (chunk == null) {
            return;
        }
        synchronized (lock) {
            pending.add(chunk);
        }
    }

    public void clear(Object chunk) {
        if (chunk == null) {
            return;
        }
        synchronized (lock) {
            pending.remove(chunk);
        }
    }

    public boolean hasPending() {
        synchronized (lock) {
            return !pending.isEmpty();
        }
    }

    /** Snapshot and remove. The owner retries or drops recovered chunks. */
    public List<Object> drain() {
        synchronized (lock) {
            if (pending.isEmpty()) {
                return List.of();
            }
            List<Object> copy = new ArrayList<>(pending);
            pending.clear();
            return copy;
        }
    }
}
