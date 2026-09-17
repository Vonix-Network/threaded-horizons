package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics;
import network.vonix.threadedhorizons.common.optimization.chunkscheduling.idle_tasks.IThreadedAnvilChunkStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage implements IThreadedAnvilChunkStorage {

    @Unique
    private static final int MAX_AUTOSAVE_QUEUE = 65_536;

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Enhanced Autosave");

    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    @Shadow protected abstract boolean saveChunkIfNeeded(ChunkHolder chunkHolder);

    @Unique
    private final Object2LongLinkedOpenHashMap<ChunkPos> dirtyChunkPosForAutoSave = new Object2LongLinkedOpenHashMap<>();

    @Unique
    private final LinkedHashSet<ChunkPos> overflowDirtyChunkPosForAutoSave = new LinkedHashSet<>();

    @Override
    public void enqueueDirtyChunkPosForAutoSave(ChunkPos chunkPos) {
        if (chunkPos == null) {
            return;
        }
        synchronized (this.dirtyChunkPosForAutoSave) {
            if (this.dirtyChunkPosForAutoSave.containsKey(chunkPos)) {
                this.dirtyChunkPosForAutoSave.putAndMoveToLast(chunkPos, System.currentTimeMillis());
                this.overflowDirtyChunkPosForAutoSave.remove(chunkPos);
                return;
            }
            if (this.dirtyChunkPosForAutoSave.size() >= MAX_AUTOSAVE_QUEUE) {
                RuntimeDiagnostics.recordRejected();
                if (this.overflowDirtyChunkPosForAutoSave.contains(chunkPos)
                        || this.overflowDirtyChunkPosForAutoSave.size() < MAX_AUTOSAVE_QUEUE) {
                    boolean firstOverflow = this.overflowDirtyChunkPosForAutoSave.isEmpty();
                    this.overflowDirtyChunkPosForAutoSave.add(chunkPos);
                    if (firstOverflow) {
                        LOGGER.warn("Enhanced autosave queue full; retaining dirty chunk {} for a later autosave cycle", chunkPos);
                    }
                } else {
                    // Fail-closed: do not grow overflow. The chunk stays unsaved so a
                    // later mutation or cycle can enqueue it when capacity exists.
                    LOGGER.warn("Enhanced autosave overflow full; leaving chunk {} dirty for a later cycle", chunkPos);
                }
                return;
            }
            this.dirtyChunkPosForAutoSave.putAndMoveToLast(chunkPos, System.currentTimeMillis());
            this.overflowDirtyChunkPosForAutoSave.remove(chunkPos);
        }
    }

    @Override
    public boolean runOneChunkAutoSave() {
        synchronized (this.dirtyChunkPosForAutoSave) {
            promoteOverflowAutosaves();
            try {
                final ObjectBidirectionalIterator<Object2LongMap.Entry<ChunkPos>> iterator = this.dirtyChunkPosForAutoSave.object2LongEntrySet().fastIterator();
                while (iterator.hasNext()) {
                    final Object2LongMap.Entry<ChunkPos> entry = iterator.next();
                    if (System.currentTimeMillis() - entry.getLongValue() < ThreadedHorizonsConfig.generalOptimizationsConfig.autoSaveConfig.delay) break;
                    if (entry.getKey() == null) {
                        iterator.remove();
                        continue;
                    }
                    if (saveDirtyChunkForAutosave(entry.getKey())) {
                        iterator.remove();
                        this.overflowDirtyChunkPosForAutoSave.remove(entry.getKey());
                        return true;
                    }
                    ChunkHolder chunkHolder = this.updatingChunkMap.get(entry.getKey().toLong());
                    if (chunkHolder == null) {
                        iterator.remove();
                        continue;
                    }
                    final CompletableFuture<ChunkAccess> savingFuture = chunkHolder.getChunkToSave();
                    if (!savingFuture.isDone()) {
                        savingFuture.handle((chunk, throwable) -> null);
                        continue;
                    }
                    boolean scheduled = this.saveChunkIfNeeded(chunkHolder);
                    ChunkAccess chunk = savingFuture.getNow(null);
                    if (scheduled || (chunk != null && chunk.isUnsaved())) {
                        if (scheduled) {
                            iterator.remove();
                        }
                        return true;
                    }
                    iterator.remove();
                }
                return false;
            } finally {
                promoteOverflowAutosaves();
            }
        }
    }

    @Unique
    private void promoteOverflowAutosaves() {
        if (this.overflowDirtyChunkPosForAutoSave.isEmpty()) {
            return;
        }
        Iterator<ChunkPos> overflow = this.overflowDirtyChunkPosForAutoSave.iterator();
        while (overflow.hasNext() && this.dirtyChunkPosForAutoSave.size() < MAX_AUTOSAVE_QUEUE) {
            ChunkPos chunkPos = overflow.next();
            overflow.remove();
            if (chunkPos == null || this.dirtyChunkPosForAutoSave.containsKey(chunkPos)) {
                continue;
            }
            this.dirtyChunkPosForAutoSave.putAndMoveToLast(chunkPos, 0L);
        }
    }

    @Unique
    protected boolean saveDirtyChunkForAutosave(ChunkPos chunkPos) {
        return false;
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void clearAutosaveQueue(CallbackInfo info) {
        synchronized (this.dirtyChunkPosForAutoSave) {
            this.dirtyChunkPosForAutoSave.clear();
            this.overflowDirtyChunkPosForAutoSave.clear();
        }
    }
}
