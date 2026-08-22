package network.vonix.threadedhorizons.common.notickvd;

import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
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

public class NoTickSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons NoTick");

    private final PlayerNoTickDistanceMap playerNoTickDistanceMap;
    private final NormalTicketDistanceMap normalTicketDistanceMap;
    private final DistanceManager chunkTicketManager;

    private final ConcurrentLinkedQueue<Runnable> pendingActions = new ConcurrentLinkedQueue<>();

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
        this.pendingActions.add(() -> this.normalTicketDistanceMap.addTicket(position, ticket));
    }

    public void onTicketRemoved(long position, Ticket<?> ticket) {
        this.pendingActions.add(() -> this.normalTicketDistanceMap.removeTicket(position, ticket));
    }

    public void addPlayerSource(ChunkPos chunkPos) {
        this.pendingActions.add(() -> this.playerNoTickDistanceMap.addSource(chunkPos));
    }

    public void removePlayerSource(ChunkPos chunkPos) {
        this.pendingActions.add(() -> this.playerNoTickDistanceMap.removeSource(chunkPos));
    }

    public void setNoTickViewDistance(int viewDistance) {
        this.pendingActions.add(() -> this.playerNoTickDistanceMap.setViewDistance(viewDistance));
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
                    if (needsAnother || !this.pendingActions.isEmpty()) {
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
        Runnable runnable;
        while ((runnable = this.pendingActions.poll()) != null) {
            try {
                runnable.run();
            } catch (Throwable t) {
                LOGGER.error("No-tick pending action failed", t);
            }
        }

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
        this.pendingActions.add(() -> {
            this.normalTicketDistanceMap.purge(age);
            // Managed no-tick ticket admission/removal is applied in scheduleTick
            // via runPendingTicketUpdates so the snapshot rebuild always observes it.
        });
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
        this.pendingActions.add(action);
    }

    /**
     * Deterministic single-thread drain used by unit tests that cannot wait on
     * the live scheduler mailbox. One cycle only (does not open a server-tick budget).
     */
    boolean drainPendingForTest() {
        this.ticketUpdateBudgetRemaining.set(PlayerNoTickDistanceMap.maxTicketUpdatesPerTick());
        boolean rebuilt = false;
        Runnable runnable;
        while ((runnable = this.pendingActions.poll()) != null) {
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
        } while (again || !this.pendingActions.isEmpty());
        return cycles;
    }

    void publishSnapshotForTest() {
        publishSnapshot();
    }
}
