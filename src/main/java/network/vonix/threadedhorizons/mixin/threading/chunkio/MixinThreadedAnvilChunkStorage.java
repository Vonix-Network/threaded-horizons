package network.vonix.threadedhorizons.mixin.threading.chunkio;

import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.GlobalExecutors;
import network.vonix.threadedhorizons.common.chunkio.ChunkSaveAdmission;
import network.vonix.threadedhorizons.common.chunkio.ChunkSaveBarrierException;
import network.vonix.threadedhorizons.common.chunkio.DirtyChunkGenerations;
import network.vonix.threadedhorizons.common.chunkio.FailedChunkSaves;
import network.vonix.threadedhorizons.common.chunkio.SaveBarrier;
import network.vonix.threadedhorizons.common.chunkio.SaveFutureTracker;
import network.vonix.threadedhorizons.common.chunkio.ThreadedHorizonsStorageVanillaInterface;
import network.vonix.threadedhorizons.common.threading.chunkio.ChunkIoMainThreadTaskUtils;
import network.vonix.threadedhorizons.common.threading.chunkio.ChunkLoadException;
import network.vonix.threadedhorizons.common.threading.chunkio.ChunkLoadResult;
import network.vonix.threadedhorizons.common.threading.chunkio.CombinedChunkLoad;
import network.vonix.threadedhorizons.common.threading.chunkio.IAsyncChunkStorage;
import network.vonix.threadedhorizons.common.threading.chunkio.ISerializingRegionBasedStorage;
import network.vonix.threadedhorizons.common.threading.chunkio.AsyncSerializationManager;
import network.vonix.threadedhorizons.common.util.SneakyThrow;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Registry;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage extends ChunkStorage implements ChunkHolder.PlayerProvider {

    public MixinThreadedAnvilChunkStorage(Path path, DataFixer dataFixer, boolean bl) {
        super(path, dataFixer, bl);
    }

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private StructureManager structureManager;

    @Shadow
    @Final
    private PoiManager poiManager;

    @Shadow
    protected abstract byte markPosition(ChunkPos chunkPos, ChunkStatus.ChunkType chunkType);

    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    protected abstract void markPositionReplaceable(ChunkPos chunkPos);

    @Shadow
    @Final
    private Supplier<DimensionDataStorage> overworldDataStorage;

    @Shadow
    @Final
    private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow
    protected abstract boolean isExistingChunkFull(ChunkPos chunkPos);

    @Shadow private ChunkGenerator generator;

    private AsyncNamedLock<ChunkPos> chunkLock = AsyncNamedLock.createFair();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        chunkLock = AsyncNamedLock.createFair();
    }

    private Set<ChunkPos> scheduledChunks = new HashSet<>();
    private final SaveFutureTracker saveTracker = new SaveFutureTracker();
    /** Failures belong to this chunk map. Another dimension must not drain them. */
    @Unique
    private final FailedChunkSaves threadedHorizons$failedSaves = new FailedChunkSaves();
    private static final int MAX_SAVE_FUTURES = SaveFutureTracker.MAX_SAVE_FUTURES;

    /**
     * @author vonix
     * @reason Fail closed for present-but-unreadable chunks. Bind POI work to the load transaction.
     */
    @Overwrite
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> scheduleChunkLoad(ChunkPos pos) {
        if (scheduledChunks == null) scheduledChunks = new HashSet<>();
        synchronized (scheduledChunks) {
            if (scheduledChunks.contains(pos)) throw new IllegalArgumentException("Already scheduled");
            scheduledChunks.add(pos);
        }

        final CompletableFuture<CompoundTag> poiData = ((IAsyncChunkStorage) this.poiManager.worker).getNbtAtAsync(pos);

        final CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future = getUpdatedChunkNbtAtAsync(pos).thenApplyAsync(compoundTag -> {
            if (compoundTag == null) {
                return new ChunkLoadResult(false, null, new ChunkIoMainThreadTaskUtils.Transaction());
            }
            if (!compoundTag.contains("Status", 8)) {
                throw new ChunkLoadException(pos, ChunkLoadException.Kind.MISSING_STATUS,
                        "Chunk at " + pos + " is present but missing Status");
            }
            ChunkIoMainThreadTaskUtils.Transaction transaction = ChunkIoMainThreadTaskUtils.open();
            try {
                return new ChunkLoadResult(true, ChunkSerializer.read(this.level, this.poiManager, pos, compoundTag), transaction);
            } catch (ChunkLoadException loadException) {
                throw loadException;
            } catch (Throwable throwable) {
                throw new ChunkLoadException(pos, ChunkLoadException.Kind.DESERIALIZE,
                        "Could not deserialize chunk " + pos, throwable);
            } finally {
                ChunkIoMainThreadTaskUtils.pop();
            }
        }, GlobalExecutors.executor).thenCombine(poiData, CombinedChunkLoad::new).thenApplyAsync(combined -> {
            try {
                ((ISerializingRegionBasedStorage) this.poiManager).update(pos, combined.poiTag());
                combined.result().transaction().drainOrThrow();
            } catch (ChunkLoadException loadException) {
                throw loadException;
            } catch (Throwable throwable) {
                throw new ChunkLoadException(pos, ChunkLoadException.Kind.POI, "POI update failed for " + pos, throwable);
            }
            if (combined.result().present()) {
                if (combined.result().chunk() == null) {
                    throw new ChunkLoadException(pos, ChunkLoadException.Kind.DESERIALIZE,
                            "Present chunk " + pos + " deserialized to empty");
                }
                this.markPosition(pos, combined.result().chunk().getStatus().getChunkType());
                return Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(combined.result().chunk());
            }
            this.markPositionReplaceable(pos);
            return Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(
                    new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY), null));
        }, this.mainThreadExecutor);
        future.whenComplete((ignored, throwable) -> {
            synchronized (scheduledChunks) {
                scheduledChunks.remove(pos);
            }
            if (throwable != null) {
                LOGGER.error("Refusing to replace unreadable chunk {}", pos, throwable);
            }
        });
        return future;
    }

    private CompletableFuture<CompoundTag> getUpdatedChunkNbtAtAsync(ChunkPos pos) {
        return chunkLock.acquireLock(pos).toCompletableFuture().thenCompose(lockToken -> ((IAsyncChunkStorage) this.worker).getNbtAtAsync(pos).thenApply(compoundTag -> {
            if (compoundTag != null) {
                try {
                    return this.upgradeChunkTag(this.level.dimension(), this.overworldDataStorage, compoundTag, this.generator.getTypeNameForDataFixer());
                } catch (Throwable throwable) {
                    throw new ChunkLoadException(pos, ChunkLoadException.Kind.DATA_FIX, "Data-fix failed for " + pos, throwable);
                }
            }
            return null;
        }).handle((tag, throwable) -> {
            lockToken.releaseLock();
            if (throwable != null) {
                SneakyThrow.sneaky(throwable);
            }
            return tag;
        }));
    }

    /**
     * @author vonix
     * @reason Keep dirty generations until the store future is durable. Unload uses this remappable save path.
     *         A generation that already has a store in flight is not admitted again: vanilla
     *         {@code saveAllChunks(true)} repeats {@code save} until it returns false, and a second
     *         true result used to enqueue a duplicate future until the cap rejected a later chunk.
     */
    @Overwrite
    private boolean save(ChunkAccess chunk) {
        this.poiManager.flush(chunk.getPos());
        if (!chunk.isUnsaved()) {
            return false;
        }
        long generation = DirtyChunkGenerations.captureForSave(chunk);
        ChunkPos chunkPos = chunk.getPos();
        boolean profilerPushed = false;
        boolean admitted = false;
        boolean handedToCallback = false;
        try {
            ChunkStatus chunkStatus = chunk.getStatus();
            if (chunkStatus.getChunkType() != ChunkStatus.ChunkType.LEVELCHUNK) {
                if (this.isExistingChunkFull(chunkPos)) {
                    return false;
                }
                if (chunkStatus == ChunkStatus.EMPTY && chunk.getAllStarts().values().stream().noneMatch(StructureStart::isValid)) {
                    return false;
                }
            }
            if (!ChunkSaveAdmission.begin(chunk, generation)) {
                // The existing future still clears unsaved only if this generation is current
                // when it completes. flushWorker/processUnloads join that future.
                return false;
            }
            admitted = true;
            this.level.getProfiler().push("chunkSave");
            profilerPushed = true;
            AsyncSerializationManager.Scope serializationScope = new AsyncSerializationManager.Scope(chunk, this.level);
            serializationScope.open();
            AsyncSerializationManager.push(serializationScope);
            CompoundTag compoundTag;
            try {
                compoundTag = ChunkSerializer.write(this.level, chunk);
            } finally {
                AsyncSerializationManager.pop(serializationScope);
            }
            if (!this.reserveSaveSlot()) {
                throw new IllegalStateException("chunk save future queue is full");
            }
            CompletableFuture<Void> stored;
            try {
                stored = this.worker instanceof ThreadedHorizonsStorageVanillaInterface owned
                        ? owned.storeOwned(chunkPos, compoundTag)
                        : this.worker.store(chunkPos, compoundTag);
            } catch (Throwable storeFailure) {
                this.saveTracker.release();
                throw storeFailure;
            }
            // Dependents of one future run last-registered first. A sibling
            // whenComplete can therefore release the tracker slot before the
            // failure is recorded. The gate completes only after the outcome,
            // and attach waits on the gate, so inFlight stays nonzero until then.
            CompletableFuture<Void> releaseGate = new CompletableFuture<>();
            stored.whenComplete((unused, throwable) -> {
                try {
                    if (throwable != null) {
                        this.threadedHorizons$failedSaves.record(chunk);
                    }
                    DirtyChunkGenerations.applyStoreOutcome(chunk, generation, throwable);
                    if (throwable == null && !chunk.isUnsaved()) {
                        this.threadedHorizons$failedSaves.clear(chunk);
                    }
                    if (throwable != null) {
                        LOGGER.error("Failed to store chunk {},{}", chunkPos.x, chunkPos.z, throwable);
                    }
                } finally {
                    try {
                        ChunkSaveAdmission.end(chunk, generation);
                    } finally {
                        if (throwable != null) {
                            releaseGate.completeExceptionally(throwable);
                        } else {
                            releaseGate.complete(null);
                        }
                    }
                }
            });
            this.saveTracker.attach(releaseGate);
            handedToCallback = true;
            if (stored.isCompletedExceptionally()) {
                // An already-failed future must not return true. saveAllChunks(true)
                // repeats save while any call returns true, and a persistent inline
                // failure would spin that loop on the server thread.
                return false;
            }
            // The storage write is deliberately asynchronous. Blocking the server thread here
            // deadlocks shutdown and can prevent vanilla from reaching its save barrier. The
            // completion callback keeps the chunk dirty until the exact generation is durable;
            // processUnloads/flushWorker join in-flight stores at the appropriate barrier.
            this.markPosition(chunkPos, chunkStatus.getChunkType());
            return true;
        } catch (Exception exception) {
            if (admitted && !handedToCallback) {
                ChunkSaveAdmission.end(chunk, generation);
            }
            if (admitted) {
                this.threadedHorizons$failedSaves.record(chunk);
            }
            DirtyChunkGenerations.applyStoreOutcome(chunk, generation, exception);
            LOGGER.error("Failed to save chunk {},{}", chunkPos.x, chunkPos.z, exception);
            return false;
        } finally {
            if (profilerPushed) {
                this.level.getProfiler().pop();
            }
        }
    }

    /**
     * Take one tracker slot. If other chunks already hold the cap, wait until
     * one of their stores finishes instead of returning {@code false}: vanilla
     * treats {@code false} as "this chunk needs no save" and stops the flush loop.
     */
    private boolean reserveSaveSlot() {
        if (this.saveTracker.tryReserve()) {
            return true;
        }
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAVE_BARRIER_TIMEOUT_MS);
        while (!this.saveTracker.tryReserve()) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0L || !this.saveTracker.awaitRoom(remainingMs)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Retry chunks whose store was attempted and failed. Runs on the caller
     * thread and does not enter {@code managedBlock}. A full future cap does
     * not call {@code save} (that wait can park for the barrier budget); the
     * chunk stays pending and the return value tells the caller to join work
     * that is already in flight.
     *
     * @return true when a retry was queued or some other store still holds a slot
     */
    private boolean scheduleFailedChunkRetries() {
        boolean scheduled = false;
        for (Object pending : this.threadedHorizons$failedSaves.drain()) {
            if (!(pending instanceof ChunkAccess chunk) || !chunk.isUnsaved()) {
                continue;
            }
            long generation = DirtyChunkGenerations.captureForSave(chunk);
            if (ChunkSaveAdmission.isAdmitted(chunk, generation)) {
                this.threadedHorizons$failedSaves.record(chunk);
                scheduled = true;
                continue;
            }
            if (this.saveTracker.inFlight() >= SaveFutureTracker.MAX_SAVE_FUTURES) {
                this.threadedHorizons$failedSaves.record(chunk);
                scheduled = true;
                continue;
            }
            boolean queued = this.save(chunk);
            if (chunk.isUnsaved()) {
                this.threadedHorizons$failedSaves.record(chunk);
            }
            if (queued || ChunkSaveAdmission.isAdmitted(chunk, DirtyChunkGenerations.captureForSave(chunk))) {
                scheduled = true;
            }
        }
        return scheduled;
    }

    @Inject(method = "processUnloads", at = @At("RETURN"))
    private void awaitUnloadedStores(BooleanSupplier keepGoing, CallbackInfo info) {
        joinSaveFutures();
        // One retry per tick. A persistent disk error stays pending for the
        // next tick and for flushWorker; it does not hold this tick for the
        // full barrier budget.
        if (this.scheduleFailedChunkRetries()) {
            joinSaveFutures();
        }
    }

    @Override
    public void flushWorker() {
        joinSaveFutures();
        // One budget for both region fences and the retry between them.
        // ChunkStorage.flushWorker is worker.synchronize(true).join(). An
        // exceptionally completed flush makes join throw CompletionException,
        // which ServerLevel's continuation does not catch. awaitStorageFence
        // is that same synchronize(true) wait, bounded, and typed.
        // RegionFile.write does not force; synchronize(true) does.
        long deadline = SaveBarrier.deadlineAfter(SAVE_BARRIER_TIMEOUT_MS);
        SaveBarrier.awaitStorageFence(this.worker.synchronize(true), deadline);
        joinSaveFutures();
        // Retry runs on this thread, outside managedBlock, and drains only this
        // chunk map's failures. The second fence waits only for the time left.
        SaveBarrier.awaitQuietAndRetryUntil(
                this.saveTracker,
                deadline,
                this.threadedHorizons$failedSaves::hasPending,
                this::scheduleFailedChunkRetries);
        SaveBarrier.awaitStorageFence(this.worker.synchronize(true), deadline);
    }

    /**
     * Durable wait for all in-flight chunk stores.
     *
     * <p>Uses the tracker's quiet-window barrier rather than a single zero-read.
     * A single read can observe a momentary gap between a completing store and
     * the follow-on store it schedules, report "idle", and let the process exit
     * with region bytes still buffered — the exact path that produced empty
     * chests with intact playerdata on a restart.
     */
    private void joinSaveFutures() {
        final boolean[] confirmed = {false};
        this.mainThreadExecutor.managedBlock(() -> {
            confirmed[0] = this.saveTracker.joinFully(SAVE_BARRIER_TIMEOUT_MS);
            return true;
        });
        if (!confirmed[0]) {
            throw new ChunkSaveBarrierException("Chunk save barrier did not drain");
        }
    }

    /** Upper bound for the shutdown drain before the barrier reports failure. */
    private static final long SAVE_BARRIER_TIMEOUT_MS = 120_000L;
}
