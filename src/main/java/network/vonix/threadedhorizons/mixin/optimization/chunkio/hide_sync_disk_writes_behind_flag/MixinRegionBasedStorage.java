package network.vonix.threadedhorizons.mixin.optimization.chunkio.hide_sync_disk_writes_behind_flag;

import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Forces durable region-file writes, unconditionally.
 *
 * <p><b>Why this mixin no longer exposes an override.</b> A previous revision
 * honoured the system property
 * {@code network.vonix.threadedhorizons.chunkio.syncDiskWrites} and let an
 * operator set {@code sync = false} on {@link RegionFileStorage}. With
 * {@code sync == false}, {@code RegionFileStorage} buffers region writes in an
 * internal queue that is <em>not</em> visible to
 * {@code SaveFutureTracker}; {@code ChunkMap#flushWorker()} therefore joins
 * TH's own in-flight stores, observes "idle", and hands control back to vanilla
 * while region bytes are still only in memory.
 *
 * <p>Observed consequence on a live server: {@code Stopping server} was
 * followed by a second {@code All chunks are saved} 2.5&nbsp;s later, and 68
 * region files were written with an identical mtime during process exit. Any
 * hard stop before that drain lost the buffered writes, which on reload
 * presented as <b>chests reverting to an older block-entity state while
 * playerdata stayed current</b> — i.e. empty chests, intact inventory, no
 * death.
 *
 * <p>This is a durability defect, not a tunable. Chunk writers must always
 * obey the same write barrier vanilla guarantees, so the flag is now forced
 * true regardless of any system property, config key, or environment value.
 * There is deliberately no opt-out: a server that silently drops player
 * container data on restart is not a configuration choice.
 *
 * <p>The mixin is kept (rather than deleted) so that a class which has chosen
 * a <em>buffered</em> region file is actively corrected at construction time
 * instead of relying on every caller to have passed the right boolean.
 *
 * <p><b>Why the value is written inline instead of via a named constant.</b>
 * Mixin validates every field of a mixin class before applying it, and a
 * {@code static} field that is not {@code private} is a hard
 * {@code InvalidMixinException} at apply time — which fails the server boot.
 * That defect shipped once in 1.1.0 as
 * {@code contains non-private static field DURABLE_WRITES_FORCED:Z}. The
 * assignment is therefore written directly, leaving this class with no static
 * field for Mixin to reject. The compiled result is identical: an
 * unconditional {@code iconst_1} store into {@code sync}, with no branch and
 * no property read.
 */
@Mixin(RegionFileStorage.class)
public class MixinRegionBasedStorage {

    @Mutable
    @Shadow @Final private boolean sync;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onPostInit(Path folder, boolean requestedSync, CallbackInfo info) {
        // Always durable. No system property, environment variable, config key,
        // or caller argument may disable this.
        // Written inline on purpose: a non-private static field in this class
        // fails Mixin validation and prevents the server from booting.
        this.sync = true;
    }

}
