package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.mixin.threading.lighting.MixinServerLightingProvider;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingRescheduleContractTest {

    @Test
    void lightingRescheduleRunsAfterVanillaClearsScheduledState() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/lighting/MixinServerLightingProvider.java"));
        assertTrue(body.contains("method = \"tryScheduleUpdate()V\""), body);
        assertTrue(body.contains("ProcessorMailbox;tell(Ljava/lang/Object;)V"), body);
        assertTrue(body.contains("runnable.run()"), body);
        assertTrue(body.contains("this.tryScheduleUpdate()"), body);
        assertFalse(body.contains("method = \"runUpdate\", at = @At(\"RETURN\")"), body);
        assertTrue(body.indexOf("runnable.run()") < body.indexOf("this.tryScheduleUpdate()"), body);
    }

    @Test
    void modifyArgHandlerUsesErasedProcessorMailboxArgumentDescriptor() throws Exception {
        var handler = MixinServerLightingProvider.class.getDeclaredMethod(
                "wrapScheduledUpdate", Object.class);
        assertEquals(Object.class, handler.getParameterTypes()[0]);
        assertEquals(Object.class, handler.getReturnType());
    }

    @Test
    void cachedForgeBytecodeClearsScheduledAfterRunningTheBatch() throws Exception {
        String disassembly = TickChunksBytecodeTest.javap(ThreadedLevelLightEngine.class);
        String lambda = TickChunksBytecodeTest.extractMethod(
                disassembly,
                "private void lambda$tryScheduleUpdate$22();");
        int runUpdate = lambda.indexOf("runUpdate:()V");
        int clearScheduled = lambda.indexOf("AtomicBoolean.set:(Z)V");
        assertTrue(runUpdate >= 0, lambda);
        assertTrue(clearScheduled > runUpdate, lambda);
    }

    @Test
    void processorMailboxRetriesExecuteAfterRejectedExecutionException() throws Exception {
        String disassembly = TickChunksBytecodeTest.javap(net.minecraft.util.thread.ProcessorMailbox.class);
        String method = TickChunksBytecodeTest.extractMethod(disassembly, "private void registerForExecution();");
        int scheduled = method.indexOf("setAsScheduled:()Z");
        int execute = method.indexOf("Executor.execute:(Ljava/lang/Runnable;)V");
        int retry = method.lastIndexOf("Executor.execute:(Ljava/lang/Runnable;)V");
        assertTrue(scheduled >= 0, method);
        assertTrue(execute > scheduled, "scheduled bit is set before dispatcher.execute()\n" + method);
        assertTrue(retry > execute, "registerForExecution must retry execute after rejection\n" + method);
        assertTrue(method.contains("RejectedExecutionException"), method);
    }
}