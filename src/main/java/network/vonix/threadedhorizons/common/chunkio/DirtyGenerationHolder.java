package network.vonix.threadedhorizons.common.chunkio;

/**
 * Per-chunk dirty-generation storage. Production {@code ChunkAccess} instances
 * implement this via mixin so block placement does not touch a global map.
 */
public interface DirtyGenerationHolder {

    long markDirtyGeneration();

    long currentDirtyGeneration();

    void forgetDirtyGeneration();

    Object dirtyGenerationLock();
}
