package network.vonix.threadedhorizons.mixin.threading.lighting;

import network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics;
import net.minecraft.util.thread.ProcessorMailbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class LightingExecutorBackpressureTest {

    @Test
    void rejectedLightingBatchNeverRunsOnSubmittingThread() throws Exception {
        ThreadFactory factory = runnable -> new Thread(runnable, "lighting-test-worker");
        ThreadPoolExecutor executor = createLightExecutor(factory, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();
        AtomicReference<Thread> rejectedTaskThread = new AtomicReference<>();
        long rejectedBefore = RuntimeDiagnostics.rejectedTasks();
        try {
            executor.execute(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { });

            RejectedExecutionException thrown = assertThrows(
                    RejectedExecutionException.class,
                    () -> executor.execute(() -> {
                        rejectedTaskRan.set(true);
                        rejectedTaskThread.set(Thread.currentThread());
                    }),
                    "Forge ProcessorMailbox retries only on RejectedExecutionException");
            assertInstanceOf(RejectedExecutionException.class, thrown);

            assertFalse(rejectedTaskRan.get(), "rejected lighting work must not execute inline");
            assertNull(rejectedTaskThread.get(), "rejected lighting work must not start a thread");
            assertTrue(RuntimeDiagnostics.rejectedTasks() > rejectedBefore,
                    "lighting rejection must be observable before the exception is thrown");
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void throwingRejectionLetsProcessorMailboxRetryAndRun() throws Exception {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "lighting-mailbox-worker");
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor real = createLightExecutor(factory, 8);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch mailboxWorkRan = new CountDownLatch(1);
        Executor dispatcher = command -> {
            if (attempts.getAndIncrement() == 0) {
                real.getRejectedExecutionHandler().rejectedExecution(command, real);
                return;
            }
            real.execute(command);
        };
        try {
            ProcessorMailbox<Runnable> mailbox = ProcessorMailbox.create(dispatcher, "light");
            mailbox.tell(mailboxWorkRan::countDown);
            assertTrue(mailboxWorkRan.await(5, TimeUnit.SECONDS),
                    "ProcessorMailbox must retry execute after RejectedExecutionException");
            assertEquals(2, attempts.get(), "mailbox must invoke dispatcher.execute exactly twice: reject then retry");
        } finally {
            real.shutdownNow();
            assertTrue(real.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void mixinHelperIsPrivateForMixinApplicator() throws Exception {
        Method helper = MixinThreadedAnvilChunkStorage.class.getDeclaredMethod(
                "createLightExecutor", ThreadFactory.class, int.class);
        assertTrue(Modifier.isPrivate(helper.getModifiers()),
                "non-private helper methods are copied into the target class and rejected by Mixin");
    }

    private static ThreadPoolExecutor createLightExecutor(ThreadFactory factory, int queueCapacity)
            throws Exception {
        Method helper = MixinThreadedAnvilChunkStorage.class.getDeclaredMethod(
                "createLightExecutor", ThreadFactory.class, int.class);
        helper.setAccessible(true);
        return (ThreadPoolExecutor) helper.invoke(null, factory, queueCapacity);
    }
}
