package network.vonix.threadedhorizons.common.optimization.reduce_allocs;

import com.google.common.base.Preconditions;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public class SimpleObjectPool<T> {

    private final Function<SimpleObjectPool<T>, T> constructor;
    private final Consumer<T> initializer;
    private final int size;

    private final Object[] cachedObjects;
    private int available = 0;

    public SimpleObjectPool(Function<SimpleObjectPool<T>, T> constructor, Consumer<T> initializer, int size) {
        this.constructor = Objects.requireNonNull(constructor);
        this.initializer = Objects.requireNonNull(initializer);
        Preconditions.checkArgument(size > 0);
        this.cachedObjects = new Object[size];
        this.size = size;
    }

    public T alloc() {
        final T object;
        synchronized (this) {
            if (this.available > 0) {
                object = (T) this.cachedObjects[--this.available];
                this.cachedObjects[this.available] = null;
            } else {
                object = this.constructor.apply(this);
            }
        }
        this.initializer.accept(object);
        return object;
    }

    public void release(T object) {
        synchronized (this) {
            if (this.available >= this.size) {
                return;
            }
            this.cachedObjects[this.available++] = object;
        }
    }

    public int available() {
        synchronized (this) {
            return this.available;
        }
    }

    public int capacity() {
        return this.size;
    }

}
