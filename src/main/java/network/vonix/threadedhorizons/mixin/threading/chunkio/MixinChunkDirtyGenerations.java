package network.vonix.threadedhorizons.mixin.threading.chunkio;

import network.vonix.threadedhorizons.common.chunkio.DirtyChunkGenerations;
import network.vonix.threadedhorizons.common.chunkio.DirtyGenerationHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkDirtyGenerations implements DirtyGenerationHolder {

    @Unique
    private final AtomicLong threadedHorizons$generation = new AtomicLong();

    @Unique
    private final Object threadedHorizons$generationLock = new Object();

    @Override
    public long markDirtyGeneration() {
        return this.threadedHorizons$generation.incrementAndGet();
    }

    @Override
    public long currentDirtyGeneration() {
        return this.threadedHorizons$generation.get();
    }

    @Override
    public void forgetDirtyGeneration() {
        this.threadedHorizons$generation.set(0L);
    }

    @Override
    public Object dirtyGenerationLock() {
        return this.threadedHorizons$generationLock;
    }

    @Inject(method = "setUnsaved", at = @At("HEAD"))
    private void onSetUnsavedAdvanceGeneration(boolean unsaved, CallbackInfo ci) {
        if (unsaved) {
            DirtyChunkGenerations.markMutated((ChunkAccess) (Object) this);
        }
    }
}
