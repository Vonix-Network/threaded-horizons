package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Disassembles compiled production classes. This does not load the mixin
 * (it extends Minecraft types) and does not apply it in a Forge runtime.
 * {@code FlushLoopAbandonsDirtyChunkTest#saveLikeCandidate} remains a
 * helper-only policy copy.
 */
class SavePathBytecodeTest {

    @Test
    void compiledSaveRegistersOutcomeBeforeReleaseAndFlushRetriesFailures() throws Exception {
        String mixin = javapClassFile(Path.of(
                "build/classes/java/main/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.class"));
        String save = extractMethod(
                mixin, "private boolean save(net.minecraft.world.level.chunk.ChunkAccess);");
        int gate = save.indexOf("CompletableFuture.\"<init>\":()V");
        int whenComplete = save.indexOf("whenComplete:(Ljava/util/function/BiConsumer;)");
        int attach = save.indexOf("SaveFutureTracker.attach:(Ljava/util/concurrent/CompletableFuture;)");
        assertTrue(gate >= 0, save);
        assertTrue(whenComplete > gate, save);
        assertTrue(attach > whenComplete, save);
        assertTrue(mixin.contains("FailedChunkSaves.record:(Ljava/lang/Object;)V"), mixin);
        assertTrue(mixin.contains("FailedChunkSaves.clear:(Ljava/lang/Object;)V"), mixin);
        assertNoStaticFailureRegistry(mixin);
        assertEqualsOneAttach(save);

        String flush = extractMethod(mixin, "public void flushWorker();");
        String syncName = "IOWorker.synchronize:(Z)Ljava/util/concurrent/CompletableFuture;";
        String fenceName = "SaveBarrier.awaitStorageFence:";
        int firstJoin = flush.indexOf("joinSaveFutures:()V");
        int deadline = flush.indexOf("SaveBarrier.deadlineAfter:(J)J");
        int sync1 = flush.indexOf(syncName);
        int fence1 = flush.indexOf(fenceName);
        int iconst1 = sync1 < 0 ? -1 : flush.lastIndexOf("iconst_1", sync1);
        int secondJoin = flush.indexOf("joinSaveFutures:()V", firstJoin + 1);
        int barrier = flush.indexOf("SaveBarrier.awaitQuietAndRetryUntil:(Lnetwork/vonix/threadedhorizons/common/chunkio/SaveFutureTracker;JLjava/util/function/BooleanSupplier;Ljava/util/function/BooleanSupplier;)V");
        int sync2 = sync1 < 0 ? -1 : flush.indexOf(syncName, sync1 + 1);
        int fence2 = fence1 < 0 ? -1 : flush.indexOf(fenceName, fence1 + 1);
        int iconst2 = sync2 < 0 ? -1 : flush.lastIndexOf("iconst_1", sync2);
        assertTrue(firstJoin >= 0, flush);
        assertFalse(flush.contains("ChunkStorage.flushWorker:()V"), flush);
        assertFalse(flush.contains("CompletableFuture.join:()"), flush);
        assertTrue(deadline > firstJoin && sync1 > deadline && fence1 > sync1, flush);
        assertTrue(iconst1 > deadline && iconst1 < sync1, flush);
        assertTrue(secondJoin > fence1, flush);
        assertTrue(barrier > secondJoin, flush);
        assertTrue(sync2 > barrier && fence2 > sync2, flush);
        assertTrue(iconst2 > barrier && iconst2 < sync2, flush);
        assertTrue(flush.indexOf("SaveBarrier.deadlineAfter:(J)J", deadline + 1) < 0, flush);
        assertTrue(flush.contains("threadedHorizons$failedSaves"), flush);

        String retries = extractMethod(mixin, "private boolean scheduleFailedChunkRetries();");
        int field = retries.indexOf("threadedHorizons$failedSaves");
        int drain = retries.indexOf("invokevirtual") ;
        int drainName = retries.indexOf("FailedChunkSaves.drain:()Ljava/util/List;");
        assertTrue(field >= 0 && drainName > field, retries);
        assertTrue(drain >= 0 && drain < drainName, retries);

        String unloads = extractMethod(
                mixin, "private void awaitUnloadedStores(java.util.function.BooleanSupplier, org.spongepowered.asm.mixin.injection.callback.CallbackInfo);");
        int unloadJoin = unloads.indexOf("joinSaveFutures:()V");
        int schedule = unloads.indexOf("scheduleFailedChunkRetries:()Z");
        int unloadJoinAgain = unloads.indexOf("joinSaveFutures:()V", unloadJoin + 1);
        assertTrue(unloadJoin >= 0 && schedule > unloadJoin && unloadJoinAgain > schedule, unloads);
    }

    @Test
    void compiledTransitionAfterSaveDoesNotResetTheGeneration() throws Exception {
        String generations = javapClassFile(Path.of(
                "build/classes/java/main/network/vonix/threadedhorizons/common/chunkio/DirtyChunkGenerations.class"));
        String transition = extractMethod(
                generations,
                "static boolean transitionAfterSave(java.lang.Object, long, boolean, java.lang.Runnable, java.util.function.BooleanSupplier, java.lang.Runnable);");
        assertFalse(transition.contains("forgetDirtyGeneration"), transition);
        assertFalse(transition.contains("remove:("), transition);
        String forget = extractMethod(
                generations, "public static void forget(java.lang.Object);");
        assertTrue(forget.contains("forgetDirtyGeneration") || forget.contains("remove:("), forget);
    }

    private static void assertNoStaticFailureRegistry(String mixin) {
        for (String line : mixin.split("\n")) {
            if (line.contains("FailedChunkSaves.")) {
                assertFalse(line.contains("invokestatic"), line);
            }
        }
        String barrier = javapQuiet("build/classes/java/main/network/vonix/threadedhorizons/common/chunkio/SaveBarrier.class");
        assertFalse(barrier.contains("FailedChunkSaves"), barrier);
    }

    private static String javapQuiet(String classFile) {
        try {
            return javapClassFile(Path.of(classFile));
        } catch (Exception exception) {
            fail(exception.toString());
            return "";
        }
    }

    private static void assertEqualsOneAttach(String save) {
        int count = 0;
        int cursor = 0;
        String needle = "SaveFutureTracker.attach:";
        while (cursor < save.length()) {
            int found = save.indexOf(needle, cursor);
            if (found < 0) {
                break;
            }
            count++;
            cursor = found + needle.length();
        }
        assertTrue(count == 1, "save must attach the reserved future once, found " + count);
    }

    private static String extractMethod(String disassembly, String header) {
        int start = disassembly.indexOf(header);
        assertTrue(start >= 0, "missing " + header);
        int searchFrom = start + header.length();
        int next = -1;
        int cursor = searchFrom;
        while (cursor < disassembly.length()) {
            int line = disassembly.indexOf('\n', cursor);
            if (line < 0) {
                break;
            }
            int content = line + 1;
            if (content + 2 < disassembly.length()
                    && disassembly.charAt(content) == ' '
                    && disassembly.charAt(content + 1) == ' '
                    && disassembly.charAt(content + 2) != ' ') {
                next = line;
                break;
            }
            cursor = line + 1;
        }
        if (next < 0) {
            return disassembly.substring(start);
        }
        return disassembly.substring(start, next);
    }

    private static String javapClassFile(Path classFile) throws Exception {
        assertTrue(classFile.toFile().isFile(), classFile.toString());
        String javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString();
        Process process = new ProcessBuilder(javap, "-c", "-p", classFile.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            fail("javap failed for " + classFile + ": " + output);
        }
        return output;
    }
}
