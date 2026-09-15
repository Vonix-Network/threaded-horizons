package network.vonix.threadedhorizons.common.lifecycle;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Registry of worker-scoped {@link ThreadLocal} caches and pools.
 * Long-lived ForkJoin workers otherwise retain those values for the process lifetime.
 */
public final class WorkerThreadLocals {

    private static final List<WeakReference<ThreadLocal<?>>> MANAGED = new CopyOnWriteArrayList<>();

    private WorkerThreadLocals() {
    }

    public static <T> ThreadLocal<T> create(Supplier<? extends T> initialValue) {
        ThreadLocal<T> local = ThreadLocal.withInitial(initialValue);
        MANAGED.add(new WeakReference<>(local));
        return local;
    }

    /**
     * Drops this thread's managed ThreadLocal values so pooled/cached objects
     * can be collected after the worker exits or a world is no longer generating.
     */
    public static void clearCurrentThread() {
        for (WeakReference<ThreadLocal<?>> reference : MANAGED) {
            ThreadLocal<?> local = reference.get();
            if (local != null) {
                local.remove();
            }
        }
        MANAGED.removeIf(reference -> reference.get() == null);
    }

    static int managedCount() {
        MANAGED.removeIf(reference -> reference.get() == null);
        return MANAGED.size();
    }
}
