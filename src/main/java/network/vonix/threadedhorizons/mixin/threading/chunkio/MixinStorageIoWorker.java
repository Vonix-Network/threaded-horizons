package network.vonix.threadedhorizons.mixin.threading.chunkio;

import network.vonix.threadedhorizons.common.threading.chunkio.ChunkIoThreadingExecutorUtils;
import network.vonix.threadedhorizons.common.threading.chunkio.IAsyncChunkStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mixin(IOWorker.class)
public abstract class MixinStorageIoWorker implements IAsyncChunkStorage {

    @Shadow protected abstract CompletableFuture<CompoundTag> loadAsync(ChunkPos pos);

    private ExecutorService threadExecutor;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;ioPool()Ljava/util/concurrent/ExecutorService;"))
    private ExecutorService redirectIoWorkerExecutor() {
        return threadExecutor = Executors.newSingleThreadExecutor(ChunkIoThreadingExecutorUtils.ioWorkerFactory);
    }

    @Override
    public CompletableFuture<CompoundTag> getNbtAtAsync(ChunkPos pos) {
        return loadAsync(pos);
    }

    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/ProcessorMailbox;close()V", shift = At.Shift.AFTER))
    private void onClose(CallbackInfo ci) {
        threadExecutor.shutdown();
        try {
            if (!threadExecutor.awaitTermination(10, TimeUnit.SECONDS) || !threadExecutor.isTerminated()) {
                throw new IllegalStateException("IO worker executor did not terminate with its queue drained");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting IO worker executor", interrupted);
        }
    }
}
