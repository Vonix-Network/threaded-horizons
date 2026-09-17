package network.vonix.threadedhorizons.common.threading.scheduler;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class SchedulerThread extends Thread implements Executor {

    static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Scheduler");

    public static SchedulerThread INSTANCE = new SchedulerThread();

    private static final ThreadLocal<int[]> NESTED_GENERATION = ThreadLocal.withInitial(() -> new int[1]);
    /** Shared by ChunkPos-only callers (existing tests and the no-world overload). */
    private static final Object DEFAULT_PIPELINE_WORLD = new Object();

    /** Bounded lock admissions per scheduler cycle so raw tasks cannot starve. */
    static final int LOCK_ADMISSION_BATCH = 32;
    static final int MAX_RAW_TASKS = 65_536;
    /** Absolute fail-closed cap. Live defaults are derived from the pipeline limit. */
    static final int MAX_PENDING_LOCKS = 65_536;
    static final int MAX_ADMITTED_LOCKS = 65_536;
    static final int MAX_PIPELINE_WAITERS = 65_536;

    private final int maxPendingLocks;
    private final int maxAdmittedLocks;

    private final ConcurrentLinkedQueue<Runnable> rawTasks = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger rawTaskCount = new java.util.concurrent.atomic.AtomicInteger();
    private final PriorityBlockingQueue<SchedulingAsyncCombinedLock<?>> pendingLocks = new PriorityBlockingQueue<>();
    private final Set<SchedulingAsyncCombinedLock<?>> admittedLocks = ConcurrentHashMap.newKeySet();

    private final Semaphore semaphore = new Semaphore(ThreadedHorizonsConfig.globalExecutorParallelism);
    private final int pipelineLimit = computePipelineLimit(
            ThreadedHorizonsConfig.globalExecutorParallelism,
            Runtime.getRuntime().maxMemory());
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final ReentrantLock pipelineLock = new ReentrantLock();
    private final Condition pipelineNotFull = this.pipelineLock.newCondition();
    /**
     * Unique (world identity, packed ChunkPos) pairs currently occupying a
     * pipeline slot. Nested maps keep occupancy dimension-aware without
     * allocating a composite key on every admit/leave.
     */
    private final Reference2ObjectOpenHashMap<Object, Long2IntOpenHashMap> pipelineChunks = new Reference2ObjectOpenHashMap<>();
    private int pipelineOccupancy;
    private final ArrayDeque<PipelineAdmission> pipelineWaiters = new ArrayDeque<>();

    private long lastRebuild = System.currentTimeMillis();
    private final AtomicBoolean hasPriorityChanges = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private SchedulerThread() {
        this(
                lockAdmissionCeiling(computePipelineLimit(
                        ThreadedHorizonsConfig.globalExecutorParallelism,
                        Runtime.getRuntime().maxMemory())),
                lockAdmissionCeiling(computePipelineLimit(
                        ThreadedHorizonsConfig.globalExecutorParallelism,
                        Runtime.getRuntime().maxMemory()))
        );
    }

    SchedulerThread(int maxPendingLocks, int maxAdmittedLocks) {
        if (maxPendingLocks <= 0 || maxAdmittedLocks <= 0) {
            throw new IllegalArgumentException("scheduler lock limits must be positive");
        }
        this.maxPendingLocks = maxPendingLocks;
        this.maxAdmittedLocks = maxAdmittedLocks;
        this.setName("Threaded Horizons scheduler");
        this.setDaemon(false);
        this.start();
    }

    @Override
    public void run() {
        while (true) {
            boolean didWork = false;

            if (doPriorityChanges()) didWork = true;

            int admitted = 0;
            while (!this.stopping.get()
                    && admitted < LOCK_ADMISSION_BATCH
                    && !pendingLocks.isEmpty()
                    && semaphore.tryAcquire()) {
                SchedulingAsyncCombinedLock<?> lock = pendingLocks.poll();
                try {
                    if (lock != null && lock.tryAcquire()) {
                        AtomicBoolean released = new AtomicBoolean();
                        Runnable releasePermit = () -> {
                            if (released.compareAndSet(false, true)) {
                                semaphore.release();
                            }
                        };
                        try {
                            lock.doAction(releasePermit);
                        } catch (Throwable throwable) {
                            LOGGER.error("Scheduler lock action failed", throwable);
                            releasePermit.run();
                        }
                        didWork = true;
                        admitted++;
                    } else {
                        semaphore.release();
                        // tryAcquire failed (or poll lost a race): do not burn the whole batch
                        // spinning on the same contended lock forever.
                        if (lock != null) {
                            admitted++;
                        }
                    }
                } catch (Throwable throwable) {
                    LOGGER.error("Scheduler lock admission failed", throwable);
                    semaphore.release();
                    if (lock != null) {
                        admitted++;
                    }
                }
            }

            // Always give raw tasks a chance, even when lock work occurred.
            Runnable runnable = pollRawTask();
            if (runnable != null) {
                didWork = true;
                try {
                    runnable.run();
                } catch (Throwable t) {
                    LOGGER.error("Scheduler task failed", t);
                }
            }

            if (this.stopping.get()) {
                this.lifecycleLock.lock();
                try {
                    drainRawTasks();
                    failRemainingLocks();
                } finally {
                    this.lifecycleLock.unlock();
                }
                break;
            }

            if (!didWork) {
                LockSupport.parkNanos("Waiting for tasks", 10_000_000);
            }
        }
    }

    public boolean addPendingLock(SchedulingAsyncCombinedLock<?> lock) {
        if (this.stopping.get()) {
            failLock(lock);
            return false;
        }
        awaitShutdownAdmissionFence();
        this.lifecycleLock.lock();
        try {
            if (this.stopping.get()) {
                failLock(lock);
                return false;
            }
            if (this.pendingLocks.size() >= this.maxPendingLocks
                    || (!this.admittedLocks.contains(lock) && this.admittedLocks.size() >= this.maxAdmittedLocks)) {
                network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics.recordRejected();
                failLock(lock);
                return false;
            }
            if (this.admittedLocks.add(lock)) {
                lock.getFuture().whenComplete((result, throwable) -> this.admittedLocks.remove(lock));
            }
            this.pendingLocks.add(lock);
        } finally {
            this.lifecycleLock.unlock();
        }
        LockSupport.unpark(this);
        return true;
    }

    public void shutdown() {
        List<PipelineAdmission> cancelled = new ArrayList<>();
        this.lifecycleLock.lock();
        try {
            this.stopping.set(true);
            this.pipelineLock.lock();
            try {
                drainWaitersLocked(cancelled);
                this.pipelineNotFull.signalAll();
            } finally {
                this.pipelineLock.unlock();
            }
            failRemainingLocks();
        } finally {
            this.lifecycleLock.unlock();
        }
        failAdmissions(cancelled);
        LockSupport.unpark(this);
    }

    public boolean isStopping() {
        return this.stopping.get();
    }

    public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        long millis = unit.toMillis(timeout);
        this.join(millis);
        return !this.isAlive();
    }

    public static synchronized void replaceInstance() {
        SchedulerThread previous = INSTANCE;
        previous.shutdown();
        try {
            previous.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        INSTANCE = new SchedulerThread();
    }

    private boolean doPriorityChanges() {
        final long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis > lastRebuild + 500) { // at most twice a second
            lastRebuild = currentTimeMillis;
            if (this.hasPriorityChanges.compareAndSet(true, false)) {
                final long startTime = System.nanoTime();
                final int size = this.pendingLocks.size();
                ArrayList<SchedulingAsyncCombinedLock<?>> tmp = new ArrayList<>(size);
                // re-add locks to reflect priority changes
                this.pendingLocks.drainTo(tmp);
                this.pendingLocks.addAll(tmp);
//                System.out.printf("Did priority changes for %d entries in %.2fms\n", size, (System.nanoTime() - startTime) / 1_000_000.0);
                return true;
            }
        }
        return false;
    }

    public void notifyPriorityChange() {
        this.hasPriorityChanges.set(true);
    }

    public int getPendingLockCount() {
        return this.pendingLocks.size();
    }

    int getAdmittedLockCount() {
        return this.admittedLocks.size();
    }

    /**
     * Maximum unique chunks allowed in the threaded-worldgen pipeline at once.
     * A chunk is a {@code (world, ChunkPos)} pair: Overworld {@code (0,0)} and
     * Nether {@code (0,0)} consume two slots. Scales with heap so {@code -Xmx2G}
     * cannot retain a full spawn-wave of NoiseChunk/protochunk state, while still
     * admitting several chunks per worker.
     */
    public static int computePipelineLimit(int parallelism, long maxMemoryBytes) {
        int workers = Math.max(1, parallelism);
        if (maxMemoryBytes < 1L) {
            maxMemoryBytes = 1L;
        }
        int byHeap = (int) Math.max(8L, maxMemoryBytes / (64L * 1024L * 1024L));
        int byParallel = Math.max(8, workers * 8);
        return Math.min(64, Math.max(byHeap, byParallel));
    }

    /**
     * Pending/admitted lock ceilings follow the pipeline, not the 65,536 absolute
     * cap. Each in-pipeline chunk holds at most one combined lock, so a small
     * multiple of the pipeline is enough for waiters and retries.
     */
    public static int lockAdmissionCeiling(int pipelineLimit) {
        int pipeline = Math.max(1, pipelineLimit);
        return Math.min(MAX_PENDING_LOCKS, Math.max(256, pipeline * 16));
    }

    public int getPipelineLimit() {
        return this.pipelineLimit;
    }

    public int getPipelineCount() {
        this.pipelineLock.lock();
        try {
            return this.pipelineOccupancy;
        } finally {
            this.pipelineLock.unlock();
        }
    }

    public int getPipelineWaiterCount() {
        this.pipelineLock.lock();
        try {
            return this.pipelineWaiters.size();
        } finally {
            this.pipelineLock.unlock();
        }
    }

    public static void enterNestedGeneration() {
        NESTED_GENERATION.get()[0]++;
    }

    public static void leaveNestedGeneration() {
        int[] depth = NESTED_GENERATION.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    private static Object resolveWorldKey(Object worldKey) {
        return worldKey == null ? DEFAULT_PIPELINE_WORLD : worldKey;
    }

    private boolean pipelineContains(Object worldKey, long packedPos) {
        Long2IntOpenHashMap refs = this.pipelineChunks.get(worldKey);
        return refs != null && refs.get(packedPos) > 0;
    }

    /**
     * Inserts or retains a (world, pos) slot. Occupancy counts unique keys, not
     * reentries. {@code retainOnReentry} is true only for callers that passed an
     * explicit world identity: nested generation of the same chunk must not
     * double-count, and the outermost leave (refcount reaching zero) releases
     * the slot. ChunkPos-only callers stay occupancy-idempotent so one leave
     * still releases after a no-op re-enter.
     */
    private boolean pipelineAdd(Object worldKey, long packedPos, boolean retainOnReentry) {
        Long2IntOpenHashMap refs = this.pipelineChunks.get(worldKey);
        if (refs == null) {
            refs = new Long2IntOpenHashMap();
            this.pipelineChunks.put(worldKey, refs);
        }
        int current = refs.get(packedPos);
        if (current > 0) {
            if (retainOnReentry) {
                refs.put(packedPos, current + 1);
            }
            return false;
        }
        refs.put(packedPos, 1);
        this.pipelineOccupancy++;
        return true;
    }

    private boolean pipelineRemove(Object worldKey, long packedPos) {
        Long2IntOpenHashMap refs = this.pipelineChunks.get(worldKey);
        if (refs == null) {
            return false;
        }
        int current = refs.get(packedPos);
        if (current <= 0) {
            return false;
        }
        if (current == 1) {
            refs.remove(packedPos);
            this.pipelineOccupancy--;
            if (refs.isEmpty()) {
                this.pipelineChunks.remove(worldKey);
            }
        } else {
            refs.put(packedPos, current - 1);
        }
        return true;
    }

    public void enterChunkPipeline(ChunkPos pos) throws InterruptedException {
        enterChunkPipeline(null, pos);
    }

    public void enterChunkPipeline(Object worldKey, ChunkPos pos) throws InterruptedException {
        if (pos == null) {
            throw new NullPointerException("pos");
        }
        if (Thread.currentThread() == this) {
            throw new IllegalStateException("scheduler thread cannot block on worldgen admission");
        }
        boolean retainOnReentry = worldKey != null;
        Object world = resolveWorldKey(worldKey);
        long packed = pos.toLong();
        boolean nested = NESTED_GENERATION.get()[0] > 0;
        while (true) {
            this.pipelineLock.lock();
            try {
                if (pipelineContains(world, packed)) {
                    pipelineAdd(world, packed, retainOnReentry);
                    return;
                }
                if (this.stopping.get()) {
                    throw new IllegalStateException("scheduler is stopping");
                }
                if (nested || this.pipelineOccupancy < this.pipelineLimit) {
                    pipelineAdd(world, packed, retainOnReentry);
                    return;
                }
            } finally {
                this.pipelineLock.unlock();
            }
            parkForPipelineSlot();
        }
    }

    /**
     * Non-blocking admission used by chunk generation. The caller receives a
     * completed future when the chunk already holds a slot, a slot is free, or
     * nested generation is allowed to exceed the limit. Otherwise the request
     * is queued and completed when {@link #leaveChunkPipeline} frees capacity.
     */
    public CompletableFuture<Void> acquireChunkPipelineAsync(ChunkPos pos) {
        return acquireChunkPipelineAsync(null, pos);
    }

    public CompletableFuture<Void> acquireChunkPipelineAsync(Object worldKey, ChunkPos pos) {
        if (pos == null) {
            throw new NullPointerException("pos");
        }
        boolean retainOnReentry = worldKey != null;
        Object world = resolveWorldKey(worldKey);
        this.pipelineLock.lock();
        try {
            if (this.stopping.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("scheduler is stopping"));
            }
            long packed = pos.toLong();
            if (pipelineContains(world, packed)
                    || NESTED_GENERATION.get()[0] > 0
                    || this.pipelineOccupancy < this.pipelineLimit) {
                pipelineAdd(world, packed, retainOnReentry);
                return CompletableFuture.completedFuture(null);
            }
            if (this.pipelineWaiters.size() >= MAX_PIPELINE_WAITERS) {
                return CompletableFuture.failedFuture(new IllegalStateException("pipeline waiter queue is full"));
            }
            PipelineAdmission waiter = new PipelineAdmission(world, pos, retainOnReentry);
            this.pipelineWaiters.addLast(waiter);
            waiter.attachCancelCallback(this);
            return waiter.future;
        } finally {
            this.pipelineLock.unlock();
        }
    }

    public void leaveChunkPipeline(ChunkPos pos) {
        leaveChunkPipeline(null, pos);
    }

    public void leaveChunkPipeline(Object worldKey, ChunkPos pos) {
        List<PipelineAdmission> released = new ArrayList<>();
        boolean stopping;
        this.pipelineLock.lock();
        try {
            if (pos != null) {
                pipelineRemove(resolveWorldKey(worldKey), pos.toLong());
            }
            stopping = this.stopping.get();
            if (stopping) {
                drainWaitersLocked(released);
            } else {
                admitWaitersLocked(released);
            }
            this.pipelineNotFull.signalAll();
        } finally {
            this.pipelineLock.unlock();
        }
        if (stopping) {
            failAdmissions(released);
        } else {
            completeAdmissions(released);
        }
    }

    private void handleAdmissionCancellation(PipelineAdmission admission) {
        List<PipelineAdmission> successors = new ArrayList<>();
        this.pipelineLock.lock();
        try {
            AdmissionState state = admission.state.get();
            if (state == AdmissionState.QUEUED) {
                // Remove only this waiter; do not charge a pipeline slot.
                this.pipelineWaiters.remove(admission);
                admission.state.compareAndSet(AdmissionState.QUEUED, AdmissionState.RELEASED);
            } else if (state == AdmissionState.ADMITTED) {
                // Ownership never transferred because complete(null) failed on a cancelled future.
                if (admission.state.compareAndSet(AdmissionState.ADMITTED, AdmissionState.RELEASED)) {
                    pipelineRemove(admission.worldKey, admission.pos.toLong());
                    if (!this.stopping.get()) {
                        admitWaitersLocked(successors);
                    }
                    this.pipelineNotFull.signalAll();
                }
            }
        } finally {
            this.pipelineLock.unlock();
        }
        if (!successors.isEmpty()) {
            completeAdmissions(successors);
        }
    }

    private void parkForPipelineSlot() throws InterruptedException {
        if (ForkJoinTask.inForkJoinPool()) {
            ForkJoinPool.managedBlock(new PipelineSlotBlocker());
            return;
        }
        this.pipelineLock.lock();
        try {
            if (this.stopping.get() || this.pipelineOccupancy < this.pipelineLimit) {
                return;
            }
            this.pipelineNotFull.await(50L, TimeUnit.MILLISECONDS);
        } finally {
            this.pipelineLock.unlock();
        }
    }

    volatile CountDownLatch shutdownAdmissionPassedStopCheck;
    volatile CountDownLatch shutdownAdmissionAllowEnqueue;

    private void awaitShutdownAdmissionFence() {
        CountDownLatch passed = this.shutdownAdmissionPassedStopCheck;
        CountDownLatch allow = this.shutdownAdmissionAllowEnqueue;
        if (passed == null || allow == null) {
            return;
        }
        passed.countDown();
        try {
            allow.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainRawTasks() {
        Runnable runnable;
        while ((runnable = pollRawTask()) != null) {
            try {
                runnable.run();
            } catch (Throwable t) {
                LOGGER.error("Scheduler task failed", t);
            }
        }
    }

    private void failRemainingLocks() {
        SchedulingAsyncCombinedLock<?> queued;
        while ((queued = this.pendingLocks.poll()) != null) {
            failLock(queued);
        }
        // Active actions retain their future and physical ownership until their
        // completion stage drains. Only queued/waiting admissions are rejected.
        for (SchedulingAsyncCombinedLock<?> admitted : this.admittedLocks) {
            failLock(admitted);
        }
    }

    private static void failLock(SchedulingAsyncCombinedLock<?> lock) {
        if (lock != null) {
            lock.failIfNotActive(new IllegalStateException("scheduler is stopping"));
        }
    }

    private void admitWaitersLocked(List<PipelineAdmission> admitted) {
        while (!this.pipelineWaiters.isEmpty()) {
            PipelineAdmission waiter = this.pipelineWaiters.peekFirst();
            if (waiter.future.isCancelled() || waiter.state.get() == AdmissionState.RELEASED) {
                this.pipelineWaiters.pollFirst();
                waiter.state.compareAndSet(AdmissionState.QUEUED, AdmissionState.RELEASED);
                continue;
            }
            long packed = waiter.pos.toLong();
            if (pipelineContains(waiter.worldKey, packed) || this.pipelineOccupancy < this.pipelineLimit) {
                this.pipelineWaiters.pollFirst();
                if (!waiter.state.compareAndSet(AdmissionState.QUEUED, AdmissionState.ADMITTED)) {
                    // Lost a race with cancellation; do not keep the slot.
                    continue;
                }
                pipelineAdd(waiter.worldKey, packed, waiter.retainOnReentry);
                admitted.add(waiter);
            } else {
                return;
            }
        }
    }

    private void drainWaitersLocked(List<PipelineAdmission> waiters) {
        PipelineAdmission waiter;
        while ((waiter = this.pipelineWaiters.pollFirst()) != null) {
            if (waiter.state.compareAndSet(AdmissionState.QUEUED, AdmissionState.RELEASED)
                    || waiter.state.get() == AdmissionState.ADMITTED) {
                waiters.add(waiter);
            }
        }
    }

    private static void completeAdmissions(List<PipelineAdmission> waiters) {
        for (PipelineAdmission waiter : waiters) {
            // complete outside the lock; ownership transfers exactly once on success
            boolean completed = waiter.future.complete(null);
            if (!completed) {
                // Cancelled or already done: release the admitted slot if still owned.
                SchedulerThread owner = waiter.owner;
                if (owner != null) {
                    owner.releaseOrphanedAdmission(waiter);
                }
            }
        }
    }

    private void releaseOrphanedAdmission(PipelineAdmission admission) {
        List<PipelineAdmission> successors = new ArrayList<>();
        this.pipelineLock.lock();
        try {
            if (admission.state.compareAndSet(AdmissionState.ADMITTED, AdmissionState.RELEASED)) {
                pipelineRemove(admission.worldKey, admission.pos.toLong());
                if (!this.stopping.get()) {
                    admitWaitersLocked(successors);
                }
                this.pipelineNotFull.signalAll();
            }
        } finally {
            this.pipelineLock.unlock();
        }
        if (!successors.isEmpty()) {
            completeAdmissions(successors);
        }
    }

    private static void failAdmissions(List<PipelineAdmission> waiters) {
        IllegalStateException stopping = new IllegalStateException("scheduler is stopping");
        for (PipelineAdmission waiter : waiters) {
            waiter.state.compareAndSet(AdmissionState.QUEUED, AdmissionState.RELEASED);
            waiter.state.compareAndSet(AdmissionState.ADMITTED, AdmissionState.RELEASED);
            waiter.future.completeExceptionally(stopping);
        }
    }

    enum AdmissionState {
        QUEUED,
        ADMITTED,
        RELEASED
    }

    private static final class PipelineAdmission {
        private final Object worldKey;
        private final ChunkPos pos;
        private final boolean retainOnReentry;
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final AtomicReference<AdmissionState> state = new AtomicReference<>(AdmissionState.QUEUED);
        private volatile SchedulerThread owner;

        private PipelineAdmission(Object worldKey, ChunkPos pos, boolean retainOnReentry) {
            this.worldKey = worldKey;
            this.pos = pos;
            this.retainOnReentry = retainOnReentry;
        }

        private void attachCancelCallback(SchedulerThread scheduler) {
            this.owner = scheduler;
            this.future.whenComplete((ignored, throwable) -> {
                if (this.future.isCancelled() || (throwable instanceof java.util.concurrent.CancellationException)) {
                    scheduler.handleAdmissionCancellation(this);
                }
            });
        }
    }

    private final class PipelineSlotBlocker implements ForkJoinPool.ManagedBlocker {
        private boolean ready;

        @Override
        public boolean isReleasable() {
            if (this.ready) {
                return true;
            }
            pipelineLock.lock();
            try {
                this.ready = stopping.get() || pipelineOccupancy < pipelineLimit;
                return this.ready;
            } finally {
                pipelineLock.unlock();
            }
        }

        @Override
        public boolean block() throws InterruptedException {
            pipelineLock.lock();
            try {
                if (stopping.get() || pipelineOccupancy < pipelineLimit) {
                    this.ready = true;
                    return true;
                }
                pipelineNotFull.await(50L, TimeUnit.MILLISECONDS);
                this.ready = stopping.get() || pipelineOccupancy < pipelineLimit;
                return this.ready;
            } finally {
                pipelineLock.unlock();
            }
        }
    }

    public int getRawTaskCount() {
        return this.rawTaskCount.get();
    }

    public int getAvailablePermits() {
        return this.semaphore.availablePermits();
    }

    @Override
    public void execute(@NotNull Runnable command) {
        this.lifecycleLock.lock();
        try {
            if (this.stopping.get()) {
                network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics.recordRejected();
                throw new IllegalStateException("scheduler is stopping");
            }
            if (this.rawTaskCount.get() >= MAX_RAW_TASKS) {
                network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics.recordRejected();
                throw new IllegalStateException("scheduler raw-task queue is full");
            }
            this.rawTasks.add(command);
            this.rawTaskCount.incrementAndGet();
        } finally {
            this.lifecycleLock.unlock();
        }
        LockSupport.unpark(this);
    }

    private Runnable pollRawTask() {
        Runnable runnable = this.rawTasks.poll();
        if (runnable != null) {
            this.rawTaskCount.decrementAndGet();
        }
        return runnable;
    }
}
