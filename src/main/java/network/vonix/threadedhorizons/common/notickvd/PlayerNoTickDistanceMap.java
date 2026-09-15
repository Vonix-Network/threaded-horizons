package network.vonix.threadedhorizons.common.notickvd;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.mixin.access.IChunkTicketManager;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2BooleanLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ChunkTracker;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerNoTickDistanceMap extends ChunkTracker {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TicketType<ChunkPos> TICKET_TYPE = TicketType.create("threadedhorizons_no_tick_vd", Comparator.comparingLong(ChunkPos::toLong));

    private static final int MAX_TICKET_UPDATES_PER_TICK = ThreadedHorizonsConfig.noTickViewDistanceConfig.updatesPerTick;

    private final LongSet sourceChunks = new LongOpenHashSet();
    private final Long2IntOpenHashMap sourceChunkRefCounts = new Long2IntOpenHashMap();
    private final Map<UUID, Long> playerSources = new HashMap<>();
    private final Long2IntOpenHashMap distanceFromNearestPlayer = new Long2IntOpenHashMap();
    private final Long2BooleanLinkedOpenHashMap pendingTicketUpdates = new Long2BooleanLinkedOpenHashMap();
    private final LongOpenHashSet managedChunkTickets = new LongOpenHashSet();

    private final DistanceManager chunkTicketManager;
    private final NoTickSystem noTickSystem;
    private volatile int viewDistance;
    private volatile int pendingTicketUpdatesCount = 0; // for easier access concurrently

    public PlayerNoTickDistanceMap(DistanceManager chunkTicketManager, NoTickSystem noTickSystem) {
        super(251, 16, 256);
        this.chunkTicketManager = chunkTicketManager;
        this.noTickSystem = noTickSystem;
        this.distanceFromNearestPlayer.defaultReturnValue(251);
        this.sourceChunkRefCounts.defaultReturnValue(0);
        this.setViewDistance(12);
    }

    @Override
    protected int getLevelFromSource(long chunkPos) {
        if (this.chunkTicketManager == null) {
            return Integer.MAX_VALUE;
        }
        final ObjectSet<ServerPlayer> players = ((IChunkTicketManager) chunkTicketManager).getPlayersByChunkPos().get(chunkPos);
        return players != null && !players.isEmpty() ? 249 - viewDistance : Integer.MAX_VALUE;
    }

    @Override
    protected int getLevel(long chunkPos) {
        return this.distanceFromNearestPlayer.get(chunkPos);
    }

    @Override
    protected void setLevel(long chunkPos, int level) {
        if (level > 249) {
            if (this.distanceFromNearestPlayer.containsKey(chunkPos)) {
                pendingTicketUpdates.put(chunkPos, false);
                this.distanceFromNearestPlayer.remove(chunkPos);
            }
        } else {
            if (!this.distanceFromNearestPlayer.containsKey(chunkPos)) {
                pendingTicketUpdates.put(chunkPos, true);
            }
            this.distanceFromNearestPlayer.put(chunkPos, level);
        }
    }

    public void addSource(ChunkPos chunkPos) {
        addSourceInternal(chunkPos.toLong());
    }

    public void removeSource(ChunkPos chunkPos) {
        removeSourceInternal(chunkPos.toLong());
    }

    public void addSource(UUID playerId, ChunkPos chunkPos) {
        if (playerId == null) {
            addSource(chunkPos);
            return;
        }
        final long next = chunkPos.toLong();
        final Long previous = this.playerSources.put(playerId, next);
        if (previous != null && previous == next) {
            return;
        }
        if (previous != null) {
            removeSourceInternal(previous);
        }
        addSourceInternal(next);
    }

    public void removeSource(UUID playerId, ChunkPos chunkPos) {
        if (playerId == null) {
            removeSource(chunkPos);
            return;
        }
        final long key = chunkPos.toLong();
        final Long active = this.playerSources.get(playerId);
        if (active == null || active != key) {
            return;
        }
        this.playerSources.remove(playerId);
        removeSourceInternal(key);
    }

    private void addSourceInternal(long chunkPos) {
        int references = this.sourceChunkRefCounts.get(chunkPos);
        this.sourceChunkRefCounts.put(chunkPos, references + 1);
        if (references == 0) {
            this.update(chunkPos, 249 - this.viewDistance, true);
            this.sourceChunks.add(chunkPos);
        }
    }

    private void removeSourceInternal(long chunkPos) {
        int references = this.sourceChunkRefCounts.get(chunkPos);
        if (references <= 0) {
            return;
        }
        if (references == 1) {
            this.sourceChunkRefCounts.remove(chunkPos);
            this.update(chunkPos, Integer.MAX_VALUE, false);
            this.sourceChunks.remove(chunkPos);
        } else {
            this.sourceChunkRefCounts.put(chunkPos, references - 1);
        }
    }

    public boolean update() {
        final int pendingRawUpdateCount = this.getQueueSize();
        if (pendingRawUpdateCount == 0) return false;
        final boolean hasLightWork = this.runUpdates(Integer.MAX_VALUE) != Integer.MAX_VALUE;
        if (hasLightWork && this.pendingTicketUpdates.size() > MAX_TICKET_UPDATES_PER_TICK) {
            // Sort only when the budget cannot apply every pending entry this tick.
            final ArrayList<Long2BooleanMap.Entry> entries = new ArrayList<>(this.pendingTicketUpdates.long2BooleanEntrySet());
            entries.sort(Comparator.comparingInt(o -> this.distanceFromNearestPlayer.get(o.getLongKey())));
            for (Long2BooleanMap.Entry entry : entries) {
                this.pendingTicketUpdates.getAndMoveToLast(entry.getLongKey());
            }
        }
        this.pendingTicketUpdatesCount = this.pendingTicketUpdates.size();
        return true;
    }

    /**
     * Applies pending ticket add/remove operations up to {@code budget} effective
     * changes. Already-satisfied entries are discarded without charging the budget.
     * The caller (NoTickSystem server-tick window) supplies the remaining budget so
     * scheduleTick recursion cannot apply more than MAX per server tick.
     *
     * @return number of effective managed membership changes applied
     */
    int runPendingTicketUpdates(int budget) {
        if (budget <= 0) {
            this.pendingTicketUpdatesCount = this.pendingTicketUpdates.size();
            return 0;
        }
        final ObjectBidirectionalIterator<Long2BooleanMap.Entry> iterator = this.pendingTicketUpdates.long2BooleanEntrySet().iterator();
        int i = 0;
        while (iterator.hasNext() && i < budget) {
            final Long2BooleanMap.Entry entry = iterator.next();
            final long chunkPos = entry.getLongKey();
            ChunkPos pos = new ChunkPos(chunkPos);
            if (entry.getBooleanValue()) {
                if (this.managedChunkTickets.add(chunkPos)) {
                    if (this.chunkTicketManager != null) {
                        this.noTickSystem.noThreadScheduler.execute(() -> this.chunkTicketManager.addTicket(TICKET_TYPE, pos, 33, pos));
                    }
                    i++;
                }
            } else {
                if (this.managedChunkTickets.remove(chunkPos)) {
                    if (this.chunkTicketManager != null) {
                        this.noThreadSchedulerExecuteRemove(pos);
                    }
                    i++;
                }
            }
            iterator.remove();
        }
        this.pendingTicketUpdatesCount = this.pendingTicketUpdates.size();
        return i;
    }

    private void noThreadSchedulerExecuteRemove(ChunkPos pos) {
        this.noTickSystem.noThreadScheduler.execute(() -> this.chunkTicketManager.removeTicket(TICKET_TYPE, pos, 33, pos));
    }

    /** Backward-compatible single-invocation helper used by older tests. */
    boolean runPendingTicketUpdates() {
        return runPendingTicketUpdates(MAX_TICKET_UPDATES_PER_TICK) > 0;
    }

    public void setViewDistance(int viewDistance) {
        final int previous = this.viewDistance;
        final int next = Mth.clamp(viewDistance, 3, 249);
        this.viewDistance = next;
        if (previous == next) {
            return;
        }
        LongIterator iterator = this.sourceChunks.iterator();
        while (iterator.hasNext()) {
            long value = iterator.nextLong();
            this.update(value, 249 - previous, false);
            this.update(value, 249 - next, true);
        }
    }

    public int getPendingTicketUpdatesCount() {
        return this.pendingTicketUpdatesCount;
    }

    public LongSet getChunks() {
        return managedChunkTickets;
    }

    // --- test hooks ---

    static int maxTicketUpdatesPerTick() {
        return MAX_TICKET_UPDATES_PER_TICK;
    }

    void seedPendingTicketUpdateForTest(long chunkPos, boolean add) {
        this.pendingTicketUpdates.put(chunkPos, add);
        this.pendingTicketUpdatesCount = this.pendingTicketUpdates.size();
    }

    int pendingTicketUpdateSizeForTest() {
        return this.pendingTicketUpdates.size();
    }

    boolean isManagedForTest(long chunkPos) {
        return this.managedChunkTickets.contains(chunkPos);
    }

    void markManagedForTest(long chunkPos) {
        this.managedChunkTickets.add(chunkPos);
    }
}
