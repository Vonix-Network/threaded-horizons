package network.vonix.threadedhorizons.common.structs;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * {@link LongSet} backed by an unboxed open hash set. Replaces a boxed
 * {@code HashSet<Long>} that previously retained an object per pending unload.
 */
public class LongHashSet implements LongSet {

    private final LongOpenHashSet delegate = new LongOpenHashSet();

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public @NotNull LongIterator iterator() {
        return delegate.iterator();
    }

    @NotNull
    @Override
    public Object @NotNull [] toArray() {
        return delegate.toArray();
    }

    @SuppressWarnings("SuspiciousToArrayCall")
    @NotNull
    @Override
    public <T> T[] toArray(@NotNull T @NotNull [] a) {
        return delegate.toArray(a);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return delegate.containsAll(c);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends Long> c) {
        return delegate.addAll(c);
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        return delegate.retainAll(c);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public boolean add(long key) {
        return delegate.add(key);
    }

    @Override
    public boolean contains(long key) {
        return delegate.contains(key);
    }

    @Override
    public long[] toLongArray() {
        return delegate.toLongArray();
    }

    @Override
    public long[] toLongArray(long[] a) {
        return delegate.toLongArray(a);
    }

    @Override
    public long[] toArray(long[] a) {
        return delegate.toArray(a);
    }

    @Override
    public boolean addAll(LongCollection c) {
        return delegate.addAll(c);
    }

    @Override
    public boolean containsAll(LongCollection c) {
        return delegate.containsAll(c);
    }

    @Override
    public boolean removeAll(LongCollection c) {
        return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(LongCollection c) {
        return delegate.retainAll(c);
    }

    @Override
    public boolean remove(long k) {
        return delegate.remove(k);
    }
}
