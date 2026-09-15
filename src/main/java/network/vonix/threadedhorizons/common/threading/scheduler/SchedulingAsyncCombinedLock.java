package network.vonix.threadedhorizons.common.threading.scheduler;

import com.google.common.base.Preconditions;
import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class SchedulingAsyncCombinedLock<T> implements Comparable<SchedulingAsyncCombinedLock<T>> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Combined Lock");

    private final AsyncNamedLock<ChunkPos> lock;
    private final ChunkPos[] names;
    private final IntSupplier priority;
    private final SchedulerThread schedulerThread;
    private final Supplier<CompletableFuture<T>> action;
    private final BooleanSupplier cancelled;
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private final AsyncLock.LockToken[] acquiredTokens;
    private AsyncLock.LockToken acquiredToken;
    private State state = State.QUEUED;

    public SchedulingAsyncCombinedLock(AsyncNamedLock<ChunkPos> lock, Set<ChunkPos> names, IntSupplier priority, SchedulerThread schedulerThread, Supplier<CompletableFuture<T>> action) {
        this(lock, names, priority, schedulerThread, action, () -> false);
    }

    public SchedulingAsyncCombinedLock(AsyncNamedLock<ChunkPos> lock, Set<ChunkPos> names, IntSupplier priority, SchedulerThread schedulerThread, Supplier<CompletableFuture<T>> action, BooleanSupplier cancelled) {
        this(lock, names.toArray(ChunkPos[]::new), priority, schedulerThread, action, cancelled);
    }

    public SchedulingAsyncCombinedLock(AsyncNamedLock<ChunkPos> lock, ChunkPos[] names, IntSupplier priority, SchedulerThread schedulerThread, Supplier<CompletableFuture<T>> action, BooleanSupplier cancelled) {
        this.lock = lock;
        this.names = names;
        this.acquiredTokens = new AsyncLock.LockToken[names.length];
        this.priority = priority;
        this.schedulerThread = schedulerThread;
        this.action = action;
        this.cancelled = cancelled;
    }

    public synchronized boolean tryAcquire() {
        if (this.state != State.QUEUED || this.future.isDone()) {
            return false;
        }
        try {
            if (this.cancelled.getAsBoolean()) {
                this.state = State.TERMINAL;
                this.future.completeExceptionally(new CancellationException());
                return false;
            }
        } catch (Throwable throwable) {
            this.state = State.TERMINAL;
            this.future.completeExceptionally(throwable);
            return false;
        }
        int acquired = 0;
        int failedAt = -1;
        for (int i = 0, namesLength = names.length; i < namesLength; i++) {
            Optional<AsyncLock.LockToken> token = this.lock.tryLock(names[i]);
            if (token.isEmpty()) {
                failedAt = i;
                break;
            }
            this.acquiredTokens[i] = token.get();
            acquired++;
        }
        if (failedAt < 0) {
            this.acquiredToken = this::releaseAcquiredTokens;
            try {
                if (this.cancelled.getAsBoolean()) {
                    this.acquiredToken.releaseLock();
                    this.state = State.TERMINAL;
                    this.future.completeExceptionally(new CancellationException());
                    return false;
                }
            } catch (Throwable throwable) {
                this.acquiredToken.releaseLock();
                this.state = State.TERMINAL;
                this.future.completeExceptionally(throwable);
                return false;
            }
            this.state = State.ACTIVE;
            return true;
        }
        releaseAcquiredPrefix(acquired);
        this.state = State.WAITING;
        this.lock.acquireLock(names[failedAt]).whenComplete((lockToken, throwable) -> {
            if (lockToken != null) {
                lockToken.releaseLock();
            }
            if (throwable != null) {
                failBeforeAction(throwable);
            } else if (requeueAfterWait()) {
                this.schedulerThread.addPendingLock(this);
            }
        });
        return false;
    }

    private void releaseAcquiredTokens() {
        releaseAcquiredPrefix(this.acquiredTokens.length);
    }

    private void releaseAcquiredPrefix(int count) {
        for (int i = 0; i < count; i++) {
            AsyncLock.LockToken token = this.acquiredTokens[i];
            this.acquiredTokens[i] = null;
            if (token != null) {
                token.releaseLock();
            }
        }
    }

    private synchronized boolean requeueAfterWait() {
        if (this.state != State.WAITING) {
            return false;
        }
        boolean cancelledNow;
        try {
            cancelledNow = this.cancelled.getAsBoolean();
        } catch (Throwable throwable) {
            this.state = State.TERMINAL;
            if (!this.future.isDone()) {
                this.future.completeExceptionally(throwable);
            }
            return false;
        }
        if (this.future.isDone() || this.schedulerThread.isStopping() || cancelledNow) {
            this.state = State.TERMINAL;
            if (!this.future.isDone()) {
                this.future.completeExceptionally(cancelledNow
                        ? new CancellationException()
                        : new IllegalStateException("scheduler is stopping"));
            }
            return false;
        }
        this.state = State.QUEUED;
        return true;
    }

    private synchronized void failBeforeAction(Throwable throwable) {
        if (this.state == State.ACTIVE || this.state == State.TERMINAL) {
            return;
        }
        this.state = State.TERMINAL;
        this.future.completeExceptionally(throwable);
    }

    synchronized void failIfNotActive(Throwable throwable) {
        failBeforeAction(throwable);
    }

    public void doAction(Runnable postAction) {
        Preconditions.checkNotNull(postAction);
        final AsyncLock.LockToken token;
        synchronized (this) {
            if (this.state != State.ACTIVE) throw new IllegalStateException("lock is not active");
            token = this.acquiredToken;
        }
        if (token == null) throw new IllegalStateException();
        boolean cancelledNow;
        try {
            cancelledNow = this.cancelled.getAsBoolean();
        } catch (Throwable throwable) {
            releaseOnce(token, postAction);
            completeTerminalExceptionally(throwable);
            return;
        }
        if (cancelledNow) {
            releaseOnce(token, postAction);
            completeTerminalExceptionally(new CancellationException());
            return;
        }
        CompletableFuture<T> stage;
        try {
            stage = this.action.get();
        } catch (Throwable throwable) {
            releaseOnce(token, postAction);
            completeTerminalExceptionally(throwable);
            return;
        }
        if (stage == null) {
            releaseOnce(token, postAction);
            completeTerminalExceptionally(new NullPointerException("future"));
            return;
        }
        stage.whenComplete((result, throwable) -> {
            releaseOnce(token, postAction);
            synchronized (this) {
                this.state = State.TERMINAL;
            }
            if (throwable != null) this.future.completeExceptionally(throwable);
            else this.future.complete(result);
        });
    }

    private void completeTerminalExceptionally(Throwable throwable) {
        synchronized (this) {
            this.state = State.TERMINAL;
        }
        this.future.completeExceptionally(throwable);
    }

    private void releaseOnce(AsyncLock.LockToken token, Runnable postAction) {
        try {
            token.releaseLock();
        } catch (Throwable t) {
            LOGGER.warn("Failed to release combined lock", t);
        }
        try {
            postAction.run();
        } catch (Throwable t) {
            LOGGER.warn("Failed to release scheduler permit", t);
        }
    }

    public CompletableFuture<T> getFuture() {
        return this.future;
    }

    @Override
    public int compareTo(@NotNull SchedulingAsyncCombinedLock o) {
        return Integer.compare(this.priority.getAsInt(), o.priority.getAsInt());
    }

    private enum State {
        QUEUED,
        WAITING,
        ACTIVE,
        TERMINAL
    }
}
