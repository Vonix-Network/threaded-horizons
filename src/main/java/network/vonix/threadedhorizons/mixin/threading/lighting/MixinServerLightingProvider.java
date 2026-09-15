package network.vonix.threadedhorizons.mixin.threading.lighting;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class MixinServerLightingProvider {

    @Shadow public abstract void tryScheduleUpdate();

    /**
     * Vanilla's scheduled mailbox runnable calls runUpdate and only then clears
     * the scheduled flag. Wrap that exact runnable so the follow-up admission
     * observes the cleared flag; injecting at runUpdate RETURN races too early.
     */
    @ModifyArg(
            method = "tryScheduleUpdate()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/thread/ProcessorMailbox;tell(Ljava/lang/Object;)V"
            ),
            index = 0
    )
    private Object wrapScheduledUpdate(Object vanillaUpdate) {
        if (!(vanillaUpdate instanceof Runnable runnable)) {
            return vanillaUpdate;
        }
        return (Runnable) () -> {
            runnable.run();
            this.tryScheduleUpdate();
        };
    }

}
