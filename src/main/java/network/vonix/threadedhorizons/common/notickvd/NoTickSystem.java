package network.vonix.threadedhorizons.common.notickvd;

import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threadly.concurrent.NoThreadScheduler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

public class NoTickSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons NoTick");
    static final int MAX_PENDING_ACTIONS = 65_536;

    private final PlayerNoTickDistanceMap playerNoTickDistanceMap;
    private final NormalTicketDistanceMap normalTicketDistanceMap;
    private final DistanceManager chunkTicketManager;

    private final ConcurrentLinkedQueue<Runnable> pendingActions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingActionCount = new AtomicInteger();

    final NoThreadScheduler noThreadScheduler = new NoThreadScheduler();

    private final AtomicBoolean isTicking = new AtomicBoolean();
    private volatile LongSet noTickOnlyChunksSnapshot = LongSets.EMPTY_SET;

    /**
     * Effective managed-ticket updates remaining for the current server tick.
     * Shared across scheduleTick recursion so a single tick cannot apply more
     * than {@link PlayerNoTickDistanceMap#maxTicketUpdatesPerTick()} effective changes.
     */
    private final AtomicInteger ticketUpdateBudgetRemaining = new AtomicInteger(0);

    /** Counts live scheduleTick mailbox admissions (including reschedules). */
    private final AtomicInteger scheduleTickAdmissions = new AtomicInteger(0);

    /** Optional test hook run after each cycle body, before the reschedule check. */
    volatile Runnable afterCycleHookForTest;

    public NoTickSystem(DistanceManager chunkTicketManager) {
        this.chunkTicketManager = chunkTicketManager;
        this.playerNoTickDistanceMap = new PlayerNoTickDistanceMap(chunkTicketManager, this);
        this.normalTicketDistanceMap = new NormalTicketDistanceMap(chunkTicketManager);
    }

    /** Unit-test constructor: no live DistanceManager / registry bootstrap. */
    static NoTickSystem createForTest() {
        return new NoTickSystem(null);
    }

    public void onTicketAdded(long position, Ticket<?> ticket) {
        enqueueOrApplySynchronously(() -> this.normalTicketDistanceMap.addTicket(position, ticket), "ticket add");
    }

    public void onTicketRemoved(long position, Ticket<?> ticket) {
        enqueueOrApplySynchronously(() -> this.normalTicketDistanceMap.removeTicket(position, ticket), "ticket remove");
    }

    public void addPlayerSource(ChunkPos chunkPos) {
        enqueueOrApplySynchronously(() -> this.playerNoTickDistanceMap.addSource(chunkPos), "player source add");
    }

    public void removePlayerSource(ChunkPos chunkPos) {
        enqueueOrApplySynchronously(() -> this.playerNoTickDistanceMap.removeSource(chunkPos), "player source remove");
    }

    public void addPlayerSource(UUID playerId, ChunkPos chunkPos) {
        enqueueOrApplySynchronously(() -> this.playerNoTickDistanceMap.addSource(playerId, chunkPos), "player source add");
    }

    public void removePlayerSource(UUID playerId, ChunkPos chunkPos) {
        enqueueOrApplySynchronously(() -> this.playerNoTickDistanceMap.removeSource(playerId, chunkPos), "player source remove");
    }

    public void setNoTickViewDistance(int viewDistance) {
        enqueueOrApplySynchronously(() -> this.playerNoTickDistanceMap.setViewDistance(viewDistance), "view distance");
    }

    public void tick() {
        this.noThreadScheduler.tick(Throwable::printStackTrace);
        // Open one server-tick ticket budget before any scheduleTick work/recursion.
        this.ticketUpdateBudgetRemaining.set(PlayerNoTickDistanceMap.maxTicketUpdatesPerTick());
        scheduleTick();
    }

    private void scheduleTick() {
        if (this.isTicking.compareAndSet(false, true)) {
            try {
                this.scheduleTickAdmissions.incrementAndGet();
                SchedulerThread.INSTANCE.execute(() -> {
                    boolean needsAnother = false;
                    try {
                        needsAnother = runOneCycle();
                    } catch (Throwable t) {
                        LOGGER.error("No-tick update failed", t);
                    } finally {
                        this.isTicking.set(false);
                    }
                    Runnable afterCycle = this.afterCycleHookForTest;
                    if (afterCycle != null) {
                        try {
                            afterCycle.run();
                        } catch (Throwable t) {
                            LOGGER.error("No-tick after-cycle hook failed", t);
                        }
                    }
                    if (needsAnother || this.pendingActionCount.get() > 0) {
                        scheduleTick();
                    }
                });
            } catch (RuntimeException scheduleFailure) {
                this.isTicking.set(false);
                throw scheduleFailure;
            }
        }
    }

    /**
     * One mailbox cycle: drain pending actions, update distance maps, apply a
     * budgeted managed-ticket batch, publish snapshot when membership changes.
     *
     * @return true when another cycle should run within the same server tick
     */
    private boolean runOneCycle() {
        drainPendingActions();

        final boolean hasNormalTicketUpdates = this.normalTicketDistanceMap.update();
        final boolean hasNoTickDistanceUpdates = this.playerNoTickDistanceMap.update();
        final int budget = Math.max(0, this.ticketUpdateBudgetRemaining.get());
        final int effectiveApplied = this.playerNoTickDistanceMap.runPendingTicketUpdates(budget);
        if (effectiveApplied > 0) {
            this.ticketUpdateBudgetRemaining.addAndGet(-effectiveApplied);
        }
        final boolean hasManagedTicketUpdates = effectiveApplied > 0;
        if (hasNormalTicketUpdates || hasNoTickDistanceUpdates || hasManagedTicketUpdates) {
            publishSnapshot();
        }

        final int remainingBudget = Math.max(0, this.ticketUpdateBudgetRemaining.get());
        final boolean moreTicketWorkThisTick =
                this.playerNoTickDistanceMap.getPendingTicketUpdatesCount() > 0 && remainingBudget > 0;
        // Distance/normal churn can require another cycle, but ticket batches
        // never exceed the server-tick budget via recursion.
        return hasNormalTicketUpdates || hasNoTickDistanceUpdates || moreTicketWorkThisTick;
    }

    private void publishSnapshot() {
        final LongSet noTickChunks = this.playerNoTickDistanceMap.getChunks();
        final LongSet normalChunks = this.normalTicketDistanceMap.getChunks();
        final LongOpenHashSet longs = new LongOpenHashSet(Math.max(16, noTickChunks.size() * 3 / 2));
        final LongIterator iterator = noTickChunks.iterator();
        while (iterator.hasNext()) {
            final long chunk = iterator.nextLong();
            if (normalChunks.contains(chunk)) continue;
            longs.add(chunk);
        }
        this.noTickOnlyChunksSnapshot = LongSets.unmodifiable(longs);
    }

    public void runPurge(long age) {
        enqueueOrApplySynchronously(() -> {
            this.normalTicketDistanceMap.purge(age);
            // Managed no-tick ticket admission/removal is applied in scheduleTick
            // via runPendingTicketUpdates so the snapshot rebuild always observes it.
        }, "ticket purge");
    }

    public LongSet getNoTickOnlyChunksSnapshot() {
        return this.noTickOnlyChunksSnapshot;
    }

    public int getPendingNoTickTicketUpdatesCount() {
        return this.playerNoTickDistanceMap.getPendingTicketUpdatesCount();
    }

    // --- package-visible test hooks (no live behavior change) ---

    PlayerNoTickDistanceMap playerNoTickDistanceMap() {
        return this.playerNoTickDistanceMap;
    }

    NormalTicketDistanceMap normalTicketDistanceMap() {
        return this.normalTicketDistanceMap;
    }

    int ticketUpdateBudgetRemainingForTest() {
        return this.ticketUpdateBudgetRemaining.get();
    }

    int scheduleTickAdmissionsForTest() {
        return this.scheduleTickAdmissions.get();
    }

    void resetScheduleTickAdmissionsForTest() {
        this.scheduleTickAdmissions.set(0);
    }

    void enqueuePendingActionForTest(Runnable action) {
        enqueueAction(action);
    }

    /**
     * Deterministic single-thread drain used by unit tests that cannot wait on
     * the live scheduler mailbox. One cycle only (does not open a server-tick budget).
     */
    boolean drainPendingForTest() {
        this.ticketUpdateBudgetRemaining.set(PlayerNoTickDistanceMap.maxTicketUpdatesPerTick());
        boolean rebuilt = false;
        Runnable runnable;
        while ((runnable = pollPendingAction()) != null) {
            runnable.run();
        }
        final boolean hasNormalTicketUpdates = this.normalTicketDistanceMap.update();
        final boolean hasNoTickDistanceUpdates = this.playerNoTickDistanceMap.update();
        final int budget = Math.max(0, this.ticketUpdateBudgetRemaining.get());
        final int effectiveApplied = this.playerNoTickDistanceMap.runPendingTicketUpdates(budget);
        if (effectiveApplied > 0) {
            this.ticketUpdateBudgetRemaining.addAndGet(-effectiveApplied);
        }
        if (hasNormalTicketUpdates || hasNoTickDistanceUpdates || effectiveApplied > 0) {
            publishSnapshot();
            rebuilt = true;
        }
        this.noThreadScheduler.tick(Throwable::printStackTrace);
        return rebuilt;
    }

    /**
     * Mirrors {@link #tick()} without the live scheduler: opens one server-tick
     * budget and recursively drains cycles until the same-tick stop conditions.
     */
    int runServerTickForTest() {
        this.noThreadScheduler.tick(Throwable::printStackTrace);
        this.ticketUpdateBudgetRemaining.set(PlayerNoTickDistanceMap.maxTicketUpdatesPerTick());
        int cycles = 0;
        boolean again;
        do {
            cycles++;
            again = runOneCycle();
            this.noThreadScheduler.tick(Throwable::printStackTrace);
            if (cycles > 10_000) {
                throw new IllegalStateException("server-tick drain did not converge");
            }
        } while (again || this.pendingActionCount.get() > 0);
        return cycles;
    }

    void publishSnapshotForTest() {
        publishSnapshot();
    }

    int pendingActionCountForTest() {
        return this.pendingActionCount.get();
    }

    private boolean enqueueAction(Runnable action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        while (true) {
            int current = this.pendingActionCount.get();
            if (current >= MAX_PENDING_ACTIONS) {
                RuntimeDiagnostics.recordRejected();
                LOGGER.warn("No-tick pending action queue full; applying critical action synchronously");
                return false;
            }
            if (this.pendingActionCount.compareAndSet(current, current + 1)) {
                break;
            }
        }
        try {
            this.pendingActions.add(action);
            return true;
        } catch (Throwable throwable) {
            this.pendingActionCount.decrementAndGet();
            LOGGER.error("No-tick action admission failed", throwable);
            return false;
        }
    }

    private void enqueueOrApplySynchronously(Runnable action, String description) {
        if (!enqueueAction(action)) {
            applyRejectedActionSynchronously(action, description);
        }
    }

    private void drainPendingActions() {
        Runnable runnable;
        while ((runnable = pollPendingAction()) != null) {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                LOGGER.error("No-tick pending action failed", throwable);
            }
        }
    }

    private void applyRejectedActionSynchronously(Runnable action, String description) {
        try {
            drainPendingActions();
            action.run();
            this.normalTicketDistanceMap.update();
            this.playerNoTickDistanceMap.update();
            final int budget = Math.max(0, this.ticketUpdateBudgetRemaining.get());
            final int effectiveApplied = this.playerNoTickDistanceMap.runPendingTicketUpdates(budget);
            if (effectiveApplied > 0) {
                this.ticketUpdateBudgetRemaining.addAndGet(-effectiveApplied);
            }
            publishSnapshot();
            LOGGER.warn("Applied rejected {} synchronously to preserve no-tick snapshot consistency", description);
        } catch (Throwable throwable) {
            LOGGER.error("Rejected {} could not be applied", description, throwable);
        }
    }

    private Runnable pollPendingAction() {
        Runnable action = this.pendingActions.poll();
        if (action != null) {
            this.pendingActionCount.decrementAndGet();
        }
        return action;
    }
}
