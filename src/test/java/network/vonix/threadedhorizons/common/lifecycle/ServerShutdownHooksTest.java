package network.vonix.threadedhorizons.common.lifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class ServerShutdownHooksTest {

    @AfterEach
    void restoreHooks() {
        ServerShutdownHooks.HOOKS.set(ServerShutdownHooks.Hooks.DEFAULT);
    }

    @Test
    void schedulerFenceAndTerminationPrecedeExecutorsAndLogging() {
        List<String> order = new ArrayList<>();
        AtomicInteger timeouts = new AtomicInteger();
        ServerShutdownHooks.HOOKS.set(new ServerShutdownHooks.Hooks() {
            @Override
            public void fenceScheduler() {
                order.add("fenceScheduler");
            }

            @Override
            public boolean awaitScheduler(long timeout, TimeUnit unit) {
                order.add("awaitScheduler");
                return true;
            }

            @Override
            public void onSchedulerTimeout(long timeout, TimeUnit unit) {
                timeouts.incrementAndGet();
                order.add("timeout");
            }

            @Override
            public void shutdownGlobalExecutors(long timeout, TimeUnit unit) {
                order.add("shutdownGlobalExecutors");
            }

            @Override
            public void shutdownLogging() {
                order.add("shutdownLogging");
            }
        });

        ServerShutdownHooks.runPostShutdown(1, TimeUnit.SECONDS);
        assertEquals(List.of("fenceScheduler", "awaitScheduler", "shutdownGlobalExecutors", "shutdownLogging"), order);
        assertEquals(0, timeouts.get());
        assertTrue(order.indexOf("shutdownGlobalExecutors") < order.indexOf("shutdownLogging"));
    }

    @Test
    void loggingStaysAvailableUntilHeldSchedulerTaskAndTerminationComplete() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        AtomicBoolean loggingAvailableDuringTask = new AtomicBoolean(false);
        AtomicBoolean loggingShutDown = new AtomicBoolean(false);
        List<String> order = new CopyOnWriteArrayList<>();

        Thread scheduler = new Thread(() -> {
            try {
                taskStarted.countDown();
                loggingAvailableDuringTask.set(!loggingShutDown.get());
                allowFinish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                taskFinished.countDown();
            }
        }, "fake-scheduler-raw");
        scheduler.setDaemon(false);

        ServerShutdownHooks.HOOKS.set(new ServerShutdownHooks.Hooks() {
            @Override
            public void fenceScheduler() {
                order.add("fenceScheduler");
                scheduler.start();
            }

            @Override
            public boolean awaitScheduler(long timeout, TimeUnit unit) throws InterruptedException {
                order.add("awaitScheduler-enter");
                assertTrue(taskStarted.await(timeout, unit));
                // Logging must still be up while the held task is running.
                assertTrue(!loggingShutDown.get());
                allowFinish.countDown();
                boolean finished = taskFinished.await(timeout, unit);
                scheduler.join(unit.toMillis(timeout));
                order.add("awaitScheduler-exit");
                return finished && !scheduler.isAlive();
            }

            @Override
            public void onSchedulerTimeout(long timeout, TimeUnit unit) {
                order.add("timeout");
            }

            @Override
            public void shutdownGlobalExecutors(long timeout, TimeUnit unit) {
                order.add("shutdownGlobalExecutors");
            }

            @Override
            public void shutdownLogging() {
                loggingShutDown.set(true);
                order.add("shutdownLogging");
            }
        });

        ServerShutdownHooks.runPostShutdown(5, TimeUnit.SECONDS);
        assertTrue(loggingAvailableDuringTask.get(), "logging must remain available through held scheduler work");
        assertTrue(loggingShutDown.get());
        assertEquals("fenceScheduler", order.get(0));
        assertTrue(order.indexOf("awaitScheduler-enter") < order.indexOf("shutdownGlobalExecutors"));
        assertTrue(order.indexOf("shutdownGlobalExecutors") < order.indexOf("shutdownLogging"));
        assertTrue(order.contains("awaitScheduler-exit"));
    }

    @Test
    void postShutdownClearsWorkerThreadLocalsAfterExecutorDrain() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/lifecycle/ServerShutdownHooks.java"));
        int executors = body.indexOf("hooks.shutdownGlobalExecutors");
        int locals = body.indexOf("WorkerThreadLocals.clearCurrentThread()");
        int logging = body.indexOf("hooks.shutdownLogging()");
        assertTrue(executors >= 0 && locals > executors && logging > locals,
                "worker ThreadLocals must clear after executor drain and before logging shutdown");
    }
}
