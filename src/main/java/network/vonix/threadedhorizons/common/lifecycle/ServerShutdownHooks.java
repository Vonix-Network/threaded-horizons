package network.vonix.threadedhorizons.common.lifecycle;

import network.vonix.threadedhorizons.common.GlobalExecutors;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ordered dedicated-server post-shutdown: fence the non-daemon scheduler first,
 * await its termination while logging is still up, then drain GlobalExecutors,
 * and only then shut down LogManager.
 */
public final class ServerShutdownHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Shutdown");
    private static final long DEFAULT_TIMEOUT_SECONDS = 10L;

    public static final AtomicReference<Hooks> HOOKS = new AtomicReference<>(Hooks.DEFAULT);

    private ServerShutdownHooks() {
    }

    public static void runPostShutdown() {
        runPostShutdown(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public static void runPostShutdown(long timeout, TimeUnit unit) {
        Hooks hooks = HOOKS.get();
        if (hooks == null) {
            hooks = Hooks.DEFAULT;
        }
        hooks.fenceScheduler();
        boolean terminated;
        try {
            terminated = hooks.awaitScheduler(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminated = false;
        }
        if (!terminated) {
            hooks.onSchedulerTimeout(timeout, unit);
        }
        hooks.shutdownGlobalExecutors(timeout, unit);
        WorkerThreadLocals.clearCurrentThread();
        hooks.shutdownLogging();
    }

    public interface Hooks {
        Hooks DEFAULT = new Hooks() {
            @Override
            public void fenceScheduler() {
                SchedulerThread.INSTANCE.shutdown();
            }

            @Override
            public boolean awaitScheduler(long timeout, TimeUnit unit) throws InterruptedException {
                return SchedulerThread.INSTANCE.awaitTermination(timeout, unit);
            }

            @Override
            public void onSchedulerTimeout(long timeout, TimeUnit unit) {
                LOGGER.error("SchedulerThread did not terminate within {} {}", timeout, unit.toString().toLowerCase());
            }

            @Override
            public void shutdownGlobalExecutors(long timeout, TimeUnit unit) {
                GlobalExecutors.shutdown(timeout, unit);
            }

            @Override
            public void shutdownLogging() {
                LogManager.shutdown();
            }
        };

        void fenceScheduler();

        boolean awaitScheduler(long timeout, TimeUnit unit) throws InterruptedException;

        void onSchedulerTimeout(long timeout, TimeUnit unit);

        void shutdownGlobalExecutors(long timeout, TimeUnit unit);

        void shutdownLogging();
    }
}
