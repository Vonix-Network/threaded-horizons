package network.vonix.threadedhorizons.common.chunkio;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local record of {@link ChunkSaveBarrierException} during one
 * {@code saveAllChunks} pass. Official {@code saveAllChunks} logs each
 * dimension's success in a second loop, after every {@code ServerLevel.save}
 * has returned, so each level keeps its own slot. The aggregate is separate
 * and is what turns the vanilla "a level ran" boolean into a failed save.
 * The signal does not clear a dirty chunk and does not make a failed store durable.
 */
public final class SaveFailureSignal {

    private static final ThreadLocal<Pass> PASS = ThreadLocal.withInitial(Pass::new);

    private SaveFailureSignal() {
    }

    /** Drop a previous pass before a new {@code saveAllChunks}. */
    public static void beginPass() {
        Pass pass = PASS.get();
        pass.active = true;
        pass.levels.clear();
        pass.logCursor = 0;
        pass.aggregate = null;
    }

    /**
     * One slot per {@code ServerLevel.save} in this pass. The success-log loop
     * walks levels in the same order and reads these slots.
     */
    public static void beginLevel() {
        Pass pass = PASS.get();
        if (!pass.active) {
            return;
        }
        pass.levels.add(null);
    }

    /** Drop the pass record after the return value has been decided. */
    public static void endPass() {
        PASS.remove();
    }

    public static boolean isBarrierFailure(Throwable failure) {
        return failure instanceof ChunkSaveBarrierException;
    }

    /**
     * @return {@code true} only for {@link ChunkSaveBarrierException}. The
     *         first failure in the current level slot and the first failure
     *         in the pass are kept. Any other throwable, including an
     *         {@code IllegalStateException} with the same words, returns false.
     */
    public static boolean recordIfBarrier(Throwable failure) {
        if (!(failure instanceof ChunkSaveBarrierException barrier)) {
            return false;
        }
        Pass pass = PASS.get();
        if (!pass.levels.isEmpty()) {
            int last = pass.levels.size() - 1;
            if (pass.levels.get(last) == null) {
                pass.levels.set(last, barrier);
            }
        }
        if (pass.aggregate == null) {
            pass.aggregate = barrier;
        }
        return true;
    }

    /**
     * Failure for the next per-dimension success line, or null when that level
     * had no barrier failure. Extra calls past the recorded levels return null
     * so a healthy level is not labeled with an earlier level's failure.
     */
    public static ChunkSaveBarrierException pollLoggedLevel() {
        Pass pass = PASS.get();
        if (pass.logCursor >= pass.levels.size()) {
            return null;
        }
        return pass.levels.get(pass.logCursor++);
    }

    public static ChunkSaveBarrierException aggregateFailure() {
        return PASS.get().aggregate;
    }

    /**
     * Vanilla {@code saveAllChunks} returns true when any level ran, including
     * after a failed chunk store. A recorded barrier failure is not that success.
     * A vanilla false stays false.
     */
    public static boolean effectiveSaveSucceeded(boolean vanillaReturn) {
        return vanillaReturn && PASS.get().aggregate == null;
    }

    private static final class Pass {
        private boolean active;
        private final List<ChunkSaveBarrierException> levels = new ArrayList<>();
        private int logCursor;
        private ChunkSaveBarrierException aggregate;
    }
}
