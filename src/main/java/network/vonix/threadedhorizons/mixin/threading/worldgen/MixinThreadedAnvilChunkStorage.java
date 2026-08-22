package network.vonix.threadedhorizons.mixin.threading.worldgen;

import com.mojang.datafixers.util.Either;
import net.minecraft.CrashReport;
import network.vonix.threadedhorizons.common.threading.scheduler.WorldGenSchedulingState;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.List;
import java.util.function.Function;

@SuppressWarnings({"InvalidInjectorMethodSignature", "UnresolvedMixinReference", "MixinAnnotationTarget"})
@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage {

    /**
     * Official 1.18.2 {@code scheduleChunkGeneration} builds an {@link Executor} from
     * synthetic {@code lambda$scheduleChunkGeneration$19} (production SRG {@code m_203098_})
     * that only hops through {@code worldgenMailbox}. Mixin AP cannot write a remappable
     * {@code @Overwrite} owner for that synthetic. Redirect the single remappable
     * {@code thenComposeAsync} that consumes that executor and run the compose immediately,
     * which is the same scheduling-overhead reduction as {@code runnable.run()}.
     */
    @Redirect(
            method = "scheduleChunkGeneration(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/ChunkStatus;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;thenComposeAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private <T, U> CompletableFuture<U> runWorldGenComposeImmediately(
            CompletableFuture<T> future,
            Function<? super T, ? extends CompletionStage<U>> fn,
            Executor executor) {
        return future.thenComposeAsync(fn, Runnable::run);
    }

    /**
     * Official-mapped generate lambda. Production Forge SRG renames it to
     * {@code m_203017_} with a different parameter order; see the grouped
     * companion injectors. {@code remap = false} is required because Mixin
     * cannot rewrite synthetic lambda names.
     */
    @Dynamic
    @Group(name = "thCaptureWorldGenHolder", min = 1, max = 1)
    @Inject(
            method = "lambda$scheduleChunkGeneration$22",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void captureWorldGenHolderOfficial(ChunkStatus status, Executor executor, ChunkHolder holder, ChunkPos pos, List<ChunkAccess> chunks, CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir) {
        WorldGenSchedulingState.setChunkHolder(holder);
    }

    /**
     * Production SRG name and argument order of {@code lambda$scheduleChunkGeneration$22}.
     * Signature: {@code (ChunkPos, ChunkHolder, ChunkStatus, Executor, List)}.
     */
    @Dynamic
    @Group(name = "thCaptureWorldGenHolder", min = 1, max = 1)
    @Inject(
            method = "m_203017_",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void captureWorldGenHolderSrg(ChunkPos pos, ChunkHolder holder, ChunkStatus status, Executor executor, List<ChunkAccess> chunks, CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir) {
        WorldGenSchedulingState.setChunkHolder(holder);
    }

    @Dynamic
    @Group(name = "thClearWorldGenHolder", min = 1, max = 1)
    @Inject(
            method = "lambda$scheduleChunkGeneration$22",
            at = @At("RETURN"),
            remap = false,
            require = 0
    )
    private void clearWorldGenHolderOfficial(CallbackInfoReturnable<?> cir) {
        WorldGenSchedulingState.clearChunkHolder();
    }

    @Dynamic
    @Group(name = "thClearWorldGenHolder", min = 1, max = 1)
    @Inject(
            method = "m_203017_",
            at = @At("RETURN"),
            remap = false,
            require = 0
    )
    private void clearWorldGenHolderSrg(CallbackInfoReturnable<?> cir) {
        WorldGenSchedulingState.clearChunkHolder();
    }

    @Dynamic
    @Redirect(
            method = "lambda$scheduleChunkGeneration$22",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;forThrowable(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;"),
            remap = false,
            require = 0
    )
    private CrashReport clearWorldGenHolderOnExceptionOfficial(Throwable throwable, String message) {
        WorldGenSchedulingState.clearChunkHolder();
        return CrashReport.forThrowable(throwable, message);
    }

    @Dynamic
    @Redirect(
            method = "m_203017_",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;m_127521_(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;"),
            remap = false,
            require = 0
    )
    private CrashReport clearWorldGenHolderOnExceptionSrg(Throwable throwable, String message) {
        WorldGenSchedulingState.clearChunkHolder();
        return CrashReport.forThrowable(throwable, message);
    }

}
