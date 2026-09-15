package network.vonix.threadedhorizons.common.notickvd;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class NoTickQueueBackpressureTest {

    @Test
    void rejectedTicketActionCompensatesNormalSnapshotWithoutThrowing() throws Exception {
        NoTickSystem system = NoTickSystem.createForTest();
        long key = ChunkPos.asLong(70, 71);
        system.playerNoTickDistanceMap().markManagedForTest(key);
        system.publishSnapshotForTest();
        assertTrue(system.getNoTickOnlyChunksSnapshot().contains(key));

        fillPendingQueue(system);

        ChunkPos pos = new ChunkPos(key);
        Ticket<ChunkPos> ticket = new Ticket<>(TicketType.UNKNOWN, 33, pos, false);
        assertDoesNotThrow(() -> system.onTicketAdded(key, ticket));
        assertFalse(system.getNoTickOnlyChunksSnapshot().contains(key),
                "a rejected add hook must compensate the snapshot immediately");

        fillPendingQueue(system);
        assertDoesNotThrow(() -> system.onTicketRemoved(key, ticket));
        assertTrue(system.getNoTickOnlyChunksSnapshot().contains(key),
                "a rejected remove hook must restore no-tick-only classification");
    }

    @Test
    void rejectedTicketActionDrainsQueuedWorkBeforeCompensation() throws Exception {
        NoTickSystem system = NoTickSystem.createForTest();
        long key = ChunkPos.asLong(80, 81);
        system.playerNoTickDistanceMap().markManagedForTest(key);
        system.publishSnapshotForTest();
        assertTrue(system.getNoTickOnlyChunksSnapshot().contains(key));

        ChunkPos pos = new ChunkPos(key);
        Ticket<ChunkPos> ticket = new Ticket<>(TicketType.UNKNOWN, 33, pos, false);
        system.enqueuePendingActionForTest(() -> system.normalTicketDistanceMap().addTicket(key, ticket));
        for (int index = 1; index < NoTickSystem.MAX_PENDING_ACTIONS; index++) {
            system.enqueuePendingActionForTest(() -> { });
        }

        assertDoesNotThrow(() -> system.onTicketRemoved(key, ticket));
        assertEquals(0, system.pendingActionCountForTest(),
                "synchronous compensation must drain older queued actions first");
        assertTrue(system.getNoTickOnlyChunksSnapshot().contains(key),
                "a later rejected remove must win over an earlier queued add");
    }

    @Test
    void rejectedPlayerSourceAndViewDistanceApplySynchronously() throws Exception {
        NoTickSystem system = NoTickSystem.createForTest();
        ChunkPos pos = new ChunkPos(3, 4);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        fillPendingQueue(system);
        assertDoesNotThrow(() -> system.addPlayerSource(pos));
        assertTrue(sourceChunks(system).contains(pos.toLong()),
                "a rejected player-source add must not be dropped");

        fillPendingQueue(system);
        assertDoesNotThrow(() -> system.removePlayerSource(pos));
        assertFalse(sourceChunks(system).contains(pos.toLong()),
                "a rejected player-source remove must not be dropped");

        fillPendingQueue(system);
        assertDoesNotThrow(() -> system.addPlayerSource(playerId, pos));
        assertTrue(sourceChunks(system).contains(pos.toLong()),
                "a rejected UUID player-source add must not be dropped");

        fillPendingQueue(system);
        assertDoesNotThrow(() -> system.removePlayerSource(playerId, pos));
        assertFalse(sourceChunks(system).contains(pos.toLong()),
                "a rejected UUID player-source remove must not be dropped");

        fillPendingQueue(system);
        assertDoesNotThrow(() -> system.setNoTickViewDistance(24));
        assertEquals(24, viewDistance(system),
                "a rejected view-distance action must not be dropped");
    }

    private static void fillPendingQueue(NoTickSystem system) {
        while (system.pendingActionCountForTest() < NoTickSystem.MAX_PENDING_ACTIONS) {
            system.enqueuePendingActionForTest(() -> { });
        }
    }

    @SuppressWarnings("unchecked")
    private static LongSet sourceChunks(NoTickSystem system) throws Exception {
        Field field = PlayerNoTickDistanceMap.class.getDeclaredField("sourceChunks");
        field.setAccessible(true);
        return (LongSet) field.get(system.playerNoTickDistanceMap());
    }

    private static int viewDistance(NoTickSystem system) throws Exception {
        Field field = PlayerNoTickDistanceMap.class.getDeclaredField("viewDistance");
        field.setAccessible(true);
        return field.getInt(system.playerNoTickDistanceMap());
    }
}
