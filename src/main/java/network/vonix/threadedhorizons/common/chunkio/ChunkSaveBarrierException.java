package network.vonix.threadedhorizons.common.chunkio;

/**
 * Failure raised only by Threaded Horizons chunk-save barriers.
 * Callers identify it by type. The message text is for logs and is not a
 * match key, so another mod's {@code IllegalStateException} with the same
 * words is not this failure.
 */
public final class ChunkSaveBarrierException extends RuntimeException {

    public ChunkSaveBarrierException(String message) {
        super(message);
    }

    public ChunkSaveBarrierException(String message, Throwable cause) {
        super(message, cause);
    }
}
