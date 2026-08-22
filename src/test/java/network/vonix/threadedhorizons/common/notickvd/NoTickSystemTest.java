package network.vonix.threadedhorizons.common.notickvd;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class NoTickSystemTest {

    @AfterEach
    void resetScheduler() {
        SchedulerThread.replaceInstance();
    }

    @Test
    void entityFilterSkipsNoTickOnlyPositions() {
        java.util.List<Long> entities = java.util.List.of(1L, 2L, 3L, 4L);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet noTickOnly = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        noTickOnly.add(2L);
        noTickOnly.add(4L);
        network.vonix.threadedhorizons.common.util.FilteringIterable<Long> filtered =
                new network.vonix.threadedhorizons.common.util.FilteringIterable<>(entities, pos -> !noTickOnly.contains(pos.longValue()));
        java.util.List<Long> seen = new java.util.ArrayList<>();
        for (Long pos : filtered) {
            seen.add(pos);
        }
        assertEquals(java.util.List.of(1L, 3L), seen);
    }

    @Test
    void filterIteratorIsExhaustive() {
        network.vonix.threadedhorizons.common.util.FilteringIterable<Integer> filtered =
                new network.vonix.threadedhorizons.common.util.FilteringIterable<>(java.util.List.of(1, 2, 3), value -> value != 2);
        java.util.Iterator<Integer> iterator = filtered.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(1, iterator.next());
        assertEquals(3, iterator.next());
        assertFalse(iterator.hasNext());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.NoSuchElementException.class, iterator::next);
    }

    @Test
    void pendingNoTickTicketAdmissionPublishesSnapshotWithoutFurtherDistanceUpdates() {
        NoTickSystem system = NoTickSystem.createForTest();
        long key = ChunkPos.asLong(10, 10);
        system.playerNoTickDistanceMap().seedPendingTicketUpdateForTest(key, true);

        assertFalse(system.getNoTickOnlyChunksSnapshot().contains(key));
        assertTrue(system.drainPendingForTest());
        assertTrue(system.getNoTickOnlyChunksSnapshot().contains(key),
                "snapshot must include managed no-tick ticket after admission");
    }

    @Test
    void managedNoTickTicketRemovalPublishesSnapshotWithoutNormalTicketUpdate() {
        NoTickSystem system = NoTickSystem.createForTest();
        long key = ChunkPos.asLong(11, 11);
        system.playerNoTickDistanceMap().markManagedForTest(key);
        system.publishSnapshotForTest();
        assertTrue(system.getNoTickOnlyChunksSnapshot().contains(key));

        system.playerNoTickDistanceMap().seedPendingTicketUpdateForTest(key, false);
        assertTrue(system.drainPendingForTest());
        assertFalse(system.getNoTickOnlyChunksSnapshot().contains(key),
                "snapshot must drop removed managed no-tick ticket");
    }

    @Test
    void snapshotMembershipEqualsNoTickMinusNormalWhenInterleaved() {
        NoTickSystem system = NoTickSystem.createForTest();
        long shared = ChunkPos.asLong(5, 5);
        long onlyNoTick = ChunkPos.asLong(6, 6);

        system.playerNoTickDistanceMap().seedPendingTicketUpdateForTest(shared, true);
        system.playerNoTickDistanceMap().seedPendingTicketUpdateForTest(onlyNoTick, true);
        assertTrue(system.drainPendingForTest());
        assertTrue(system.getNoTickOnlyChunksSnapshot().contains(shared));
        assertTrue(system.getNoTickOnlyChunksSnapshot().contains(onlyNoTick));

        system.normalTicketDistanceMap().markChunkForTest(shared);
        system.publishSnapshotForTest();
        LongSet snap = system.getNoTickOnlyChunksSnapshot();
        assertFalse(snap.contains(shared), "normal membership excludes shared from no-tick-only snapshot");
        assertTrue(snap.contains(onlyNoTick));

        system.normalTicketDistanceMap().unmarkChunkForTest(shared);
        system.publishSnapshotForTest();
        snap = system.getNoTickOnlyChunksSnapshot();
        assertTrue(snap.contains(shared));
        assertTrue(snap.contains(onlyNoTick));
    }

    @Test
    void pendingTicketBudgetSchedulesExactlyMaxEffectiveUpdates() {
        NoTickSystem system = NoTickSystem.createForTest();
        PlayerNoTickDistanceMap map = system.playerNoTickDistanceMap();
        int max = PlayerNoTickDistanceMap.maxTicketUpdatesPerTick();
        for (int index = 0; index < max + 2; index++) {
            map.seedPendingTicketUpdateForTest(ChunkPos.asLong(index, 80), true);
        }
        int applied = map.runPendingTicketUpdates(max);
        assertEquals(max, applied);
        int managed = 0;
        for (int index = 0; index < max + 2; index++) {
            if (map.isManagedForTest(ChunkPos.asLong(index, 80))) {
                managed++;
            }
        }
        assertEquals(max, managed, "exactly MAX effective additions per purge");
        assertEquals(2, map.pendingTicketUpdateSizeForTest(), "remainder stays pending");
    }

    @Test
    void noOpsAreDiscardedWithoutChargingBudget() {
        NoTickSystem system = NoTickSystem.createForTest();
        PlayerNoTickDistanceMap map = system.playerNoTickDistanceMap();
        int max = PlayerNoTickDistanceMap.maxTicketUpdatesPerTick();

        long already = ChunkPos.asLong(1, 81);
        map.markManagedForTest(already);
        map.seedPendingTicketUpdateForTest(already, true); // no-op add
        map.seedPendingTicketUpdateForTest(ChunkPos.asLong(2, 81), false); // no-op remove

        for (int index = 0; index < max; index++) {
            map.seedPendingTicketUpdateForTest(ChunkPos.asLong(100 + index, 81), true);
        }
        map.seedPendingTicketUpdateForTest(ChunkPos.asLong(999, 81), true);

        int applied = map.runPendingTicketUpdates(max);
        assertEquals(max, applied);
        int managedNew = 0;
        for (int index = 0; index < max; index++) {
            if (map.isManagedForTest(ChunkPos.asLong(100 + index, 81))) {
                managedNew++;
            }
        }
        assertEquals(max, managedNew);
        assertTrue(map.isManagedForTest(already));
        assertFalse(map.isManagedForTest(ChunkPos.asLong(999, 81)),
                "budget must be spent only on effective changes so the extra remains pending");
        assertEquals(1, map.pendingTicketUpdateSizeForTest());
    }

    /**
     * TH-NOTICK-TEST-003: traverse the live scheduleTick/reschedule path on
     * SchedulerThread (not the offline runServerTickForTest loop). One shared
     * server-tick budget must cover every admitted cycle.
     */
    @Test
    void serverTickPurgePathAppliesAtMostMaxEffectiveUpdatesDespiteReschedule() throws Exception {
        NoTickSystem system = NoTickSystem.createForTest();
        PlayerNoTickDistanceMap map = system.playerNoTickDistanceMap();
        int max = PlayerNoTickDistanceMap.maxTicketUpdatesPerTick();

        for (int index = 0; index < max * 3; index++) {
            map.seedPendingTicketUpdateForTest(ChunkPos.asLong(index, 90), true);
        }
        // No-ops must not charge the shared server-tick budget.
        map.markManagedForTest(ChunkPos.asLong(0, 90));
        map.seedPendingTicketUpdateForTest(ChunkPos.asLong(0, 90), true);
        map.seedPendingTicketUpdateForTest(ChunkPos.asLong(5000, 90), false);

        system.runPurge(0L);
        system.resetScheduleTickAdmissionsForTest();

        AtomicInteger afterCycleInvocations = new AtomicInteger();
        AtomicBoolean injectedSecondCycleWork = new AtomicBoolean(false);
        CountDownLatch secondCycleFinished = new CountDownLatch(1);
        AtomicInteger managedAfterFirstCycle = new AtomicInteger(-1);

        // After the first live cycle completes, enqueue more pending work so the
        // real scheduleTick reschedule branch runs a second mailbox admission.
        // Production control flow is unchanged: budget is not reopened.
        system.afterCycleHookForTest = () -> {
            int invocation = afterCycleInvocations.incrementAndGet();
            if (invocation == 1) {
                managedAfterFirstCycle.set(countManaged(map, max * 3, 90));
                if (injectedSecondCycleWork.compareAndSet(false, true)) {
                    system.enqueuePendingActionForTest(() -> {
                        // no-op body; presence alone forces the reschedule drain
                    });
                }
            } else if (invocation >= 2) {
                secondCycleFinished.countDown();
            }
        };

        try {
            // Live server-tick entry: opens budget once, then scheduleTick on SchedulerThread.
            system.tick();

            assertTrue(secondCycleFinished.await(5, TimeUnit.SECONDS),
                    "live scheduleTick must reschedule a second cycle; admissions="
                            + system.scheduleTickAdmissionsForTest());
            waitUntilIdle(system, 5);

            assertTrue(system.scheduleTickAdmissionsForTest() >= 2,
                    "expected at least two live scheduleTick admissions, got "
                            + system.scheduleTickAdmissionsForTest());
            assertTrue(afterCycleInvocations.get() >= 2);

            int managedAfterFirst = managedAfterFirstCycle.get();
            assertTrue(managedAfterFirst >= 0, "first-cycle observation missing");
            // First cycle may apply at most MAX effective admissions (+ pre-marked key).
            assertTrue(managedAfterFirst <= max + 1,
                    "first live cycle exceeded shared budget; managed=" + managedAfterFirst + " max=" + max);

            int managedFinal = countManaged(map, max * 3, 90);
            // Second cycle must not apply additional effective ticket updates once budget is spent.
            assertEquals(managedAfterFirst, managedFinal,
                    "rescheduled cycle must share the same exhausted ticket budget");
            assertTrue(managedFinal <= max + 1,
                    "server tick total must stay within MAX effective; managed=" + managedFinal);
            assertTrue(map.pendingTicketUpdateSizeForTest() > 0,
                    "remainder must stay pending for a later server tick");
            assertEquals(0, system.ticketUpdateBudgetRemainingForTest(),
                    "budget must be exhausted after the live tick window");
        } finally {
            system.afterCycleHookForTest = null;
        }

        // Next server tick opens a fresh budget and may continue.
        int beforeSecondTick = countManaged(map, max * 3, 90);
        system.resetScheduleTickAdmissionsForTest();
        system.tick();
        waitUntilIdle(system, 5);
        int afterSecondTick = countManaged(map, max * 3, 90);
        assertTrue(afterSecondTick >= beforeSecondTick);
        assertTrue(afterSecondTick - beforeSecondTick <= max,
                "second server tick also capped at MAX; delta=" + (afterSecondTick - beforeSecondTick));
    }

    private static int countManaged(PlayerNoTickDistanceMap map, int count, int z) {
        int managed = 0;
        for (int index = 0; index < count; index++) {
            if (map.isManagedForTest(ChunkPos.asLong(index, z))) {
                managed++;
            }
        }
        return managed;
    }

    private static void waitUntilIdle(NoTickSystem system, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (system.scheduleTickAdmissionsForTest() > 0
                    && system.ticketUpdateBudgetRemainingForTest() == 0
                    && SchedulerThread.INSTANCE.getRawTaskCount() == 0) {
                // Allow in-flight mailbox callbacks to finish.
                Thread.sleep(20L);
                if (SchedulerThread.INSTANCE.getRawTaskCount() == 0) {
                    return;
                }
            }
            Thread.sleep(10L);
        }
    }
}
