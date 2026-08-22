package network.vonix.threadedhorizons.mixin.util.log4j2shutdownhookisnomore;

import net.minecraft.server.dedicated.DedicatedServer;
import network.vonix.threadedhorizons.common.lifecycle.ServerShutdownHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DedicatedServer.class)
public class MixinMinecraftDedicatedServer {

    @Inject(method = "onServerExit", at = @At("RETURN"))
    private void onPostShutdown(CallbackInfo ci) {
        ServerShutdownHooks.runPostShutdown();
    }

}
