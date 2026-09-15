package network.vonix.threadedhorizons.mixin.threading.lighting;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage {

    private static final int MAX_LIGHT_QUEUE = 65_536;
    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Lighting");
    private static final RejectedExecutionHandler LIGHT_REJECTION_HANDLER = (runnable, executor) -> {
        RuntimeDiagnostics.recordRejected();
        LOGGER.warn("Lighting update rejected because the dedicated lighting queue is full or closed");
        throw new RejectedExecutionException("lighting executor rejected work");
    };

    @Shadow
    @Final
    private ServerLevel level;
    private ExecutorService lightThread = null;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/ProcessorMailbox;create(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/ProcessorMailbox;"))
    private ProcessorMailbox<Runnable> onLightExecutorInit(Executor executor, String name) {
        if (!name.equals("light")) return ProcessorMailbox.create(executor, name);
        lightThread = createLightExecutor(
                new ThreadFactoryBuilder().setPriority(Thread.NORM_PRIORITY - 1).setDaemon(true).setNameFormat(String.format("%s - Light", this.level.dimension().location().toDebugFileName())).build(),
                MAX_LIGHT_QUEUE
        );
        return ProcessorMailbox.create(lightThread, name);
    }

    private static ThreadPoolExecutor createLightExecutor(ThreadFactory threadFactory, int queueCapacity) {
        return new ThreadPoolExecutor(
                1, 1,
                0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                LIGHT_REJECTION_HANDLER
        );
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void afterClose(CallbackInfo info) {
        if (lightThread == null) {
            return;
        }
        lightThread.shutdown();
        try {
            if (!lightThread.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lighting executor did not terminate with its queue drained");
            }
            if (lightThread instanceof ThreadPoolExecutor executor && !executor.getQueue().isEmpty()) {
                throw new IllegalStateException("lighting executor terminated with queued work");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting lighting executor", interrupted);
        }
    }

}
