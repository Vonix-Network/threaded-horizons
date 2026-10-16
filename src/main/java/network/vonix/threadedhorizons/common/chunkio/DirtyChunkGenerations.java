package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Per-chunk mutation generations. Only mutations advance the counter.
 * A save captures the current generation and does not advance it. A durable
 * completion leaves that number in place, so a later mutation cannot reuse it.
 * An older completion must not clear unsaved state for a newer mutation.
 * {@link #forget(Object)} is the only path that returns a live key to 0.
 */
public final class DirtyChunkGenerations {

    /**
     * Fixed-size hash-striped monitors. Keyed lock maps retain every ephemeral
     * chunk key forever; stripes stay bounded and are never removed on forget
     * so concurrent callers cannot split across different monitors.
     * Generation maps are per-stripe {@link WeakHashMap}s so dropped chunks
     * do not pin {@link ChunkAccess} strongly until an explicit forget.
     */
    static final int LOCK_STRIPE_COUNT = 64;
    private static final Object[] LOCK_STRIPES = new Object[LOCK_STRIPE_COUNT];
    @SuppressWarnings("unchecked")
    private static final WeakHashMap<Object, AtomicLong>[] GENERATIONS = new WeakHashMap[LOCK_STRIPE_COUNT];

    static {
        for (int index = 0; index < LOCK_STRIPE_COUNT; index++) {
            LOCK_STRIPES[index] = new Object();
            GENERATIONS[index] = new WeakHashMap<>();
        }
    }

    public static final TransitionHooks hooks = new TransitionHooks();

    private DirtyChunkGenerations() {
    }

    public static long markMutated(Object chunk) {
        if (chunk == null) {
            return 0L;
        }
        if (chunk instanceof DirtyGenerationHolder holder) {
            synchronized (holder.dirtyGenerationLock()) {
                return holder.markDirtyGeneration();
            }
        }
        synchronized (lockFor(chunk)) {
            return generationsFor(chunk).computeIfAbsent(chunk, unused -> new AtomicLong()).incrementAndGet();
        }
    }

    public static long captureForSave(Object chunk) {
        if (chunk == null) {
            return 0L;
        }
        if (chunk instanceof DirtyGenerationHolder holder) {
            synchronized (holder.dirtyGenerationLock()) {
                return holder.currentDirtyGeneration();
            }
        }
        synchronized (lockFor(chunk)) {
            return currentUnlocked(chunk);
        }
    }

    public static long current(Object chunk) {
        if (chunk == null) {
            return 0L;
        }
        if (chunk instanceof DirtyGenerationHolder holder) {
            synchronized (holder.dirtyGenerationLock()) {
                return holder.currentDirtyGeneration();
            }
        }
        synchronized (lockFor(chunk)) {
            return currentUnlocked(chunk);
        }
    }

    public static boolean isCurrent(Object chunk, long generation) {
        return current(chunk) == generation;
    }

    public static boolean shouldClearUnsaved(Object chunk, long saveGeneration, boolean durableSuccess) {
        return durableSuccess && isCurrent(chunk, saveGeneration);
    }

    public static void applyStoreOutcome(ChunkAccess chunk, long saveGeneration, Throwable throwable) {
        if (chunk == null) {
            return;
        }
        transitionAfterSave(
                chunk,
                saveGeneration,
                throwable == null,
                () -> chunk.setUnsaved(false),
                chunk::isUnsaved,
                () -> chunk.setUnsaved(true)
        );
    }

    public static boolean applyStoreOutcome(Object chunk, long saveGeneration, boolean durableSuccess, AtomicBoolean unsaved) {
        if (unsaved == null) {
            return transitionAfterSave(chunk, saveGeneration, durableSuccess, null, null, null);
        }
        return transitionAfterSave(
                chunk,
                saveGeneration,
                durableSuccess,
                () -> unsaved.set(false),
                unsaved::get,
                () -> unsaved.set(true)
        );
    }

    public static void forget(Object chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk instanceof DirtyGenerationHolder holder) {
            synchronized (holder.dirtyGenerationLock()) {
                holder.forgetDirtyGeneration();
            }
            return;
        }
        synchronized (lockFor(chunk)) {
            generationsFor(chunk).remove(chunk);
        }
    }

    static boolean transitionAfterSave(
            Object chunk,
            long saveGeneration,
            boolean durableSuccess,
            Runnable clearUnsaved,
            BooleanSupplier isUnsaved,
            Runnable markUnsaved
    ) {
        if (chunk == null) {
            return false;
        }
        Object lock = lockForObject(chunk);
        long observed;
        synchronized (lock) {
            observed = currentUnlockedObject(chunk);
        }
        hooks.fireAfterValidation(chunk, observed);
        synchronized (lock) {
            if (durableSuccess && currentUnlockedObject(chunk) == saveGeneration) {
                if (clearUnsaved != null) {
                    clearUnsaved.run();
                }
                // clearUnsaved can re-enter markMutated on this same lock
                // (setUnsaved(true) from a nested editor). Do not report the
                // captured generation cleared, and do not reset it, if that
                // nested mutation advanced the counter.
                if (currentUnlockedObject(chunk) != saveGeneration) {
                    return false;
                }
                return true;
            }
            if (!durableSuccess && isUnsaved != null && markUnsaved != null && !isUnsaved.getAsBoolean()) {
                markUnsaved.run();
            }
            return false;
        }
    }

    private static Object lockForObject(Object chunk) {
        if (chunk instanceof DirtyGenerationHolder holder) {
            return holder.dirtyGenerationLock();
        }
        return lockFor(chunk);
    }

    private static long currentUnlockedObject(Object chunk) {
        if (chunk instanceof DirtyGenerationHolder holder) {
            return holder.currentDirtyGeneration();
        }
        return currentUnlocked(chunk);
    }

    private static long currentUnlocked(Object chunk) {
        AtomicLong generation = generationsFor(chunk).get(chunk);
        return generation == null ? 0L : generation.get();
    }

    private static int stripeIndex(Object chunk) {
        return System.identityHashCode(chunk) & (LOCK_STRIPE_COUNT - 1);
    }

    private static WeakHashMap<Object, AtomicLong> generationsFor(Object chunk) {
        return GENERATIONS[stripeIndex(chunk)];
    }

    private static Object lockFor(Object chunk) {
        return LOCK_STRIPES[stripeIndex(chunk)];
    }

    /** Test/diagnostic: fixed stripe count, never grows with ephemeral keys. */
    public static int lockStripeCount() {
        return LOCK_STRIPE_COUNT;
    }

    /** Test/diagnostic: live generation entries across all stripes. */
    public static int generationEntryCount() {
        int total = 0;
        for (int index = 0; index < LOCK_STRIPE_COUNT; index++) {
            synchronized (LOCK_STRIPES[index]) {
                total += GENERATIONS[index].size();
            }
        }
        return total;
    }

    public static final class TransitionHooks {
        public volatile BiConsumer<Object, Long> afterValidationBeforeClear;

        void fireAfterValidation(Object chunk, long observedGeneration) {
            BiConsumer<Object, Long> hook = this.afterValidationBeforeClear;
            if (hook != null) {
                hook.accept(chunk, observedGeneration);
            }
        }

        public void reset() {
            this.afterValidationBeforeClear = null;
        }
    }
}
