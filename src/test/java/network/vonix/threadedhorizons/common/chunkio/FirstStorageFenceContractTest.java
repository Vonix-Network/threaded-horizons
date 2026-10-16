package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract for the P8-review blocker: the FIRST storage fence in
 * {@code ChunkMap.flushWorker} must be bounded and translated.
 *
 * <p>The P5 predecessor called {@code super.flushWorker()} — official
 * {@code ChunkStorage.flushWorker} is {@code IOWorker.synchronize(true).join()}.
 * A failed region flush therefore completed the synchronize future exceptionally
 * and {@code join()} leaked a {@code CompletionException} before TH's typed
 * barrier path, so {@code MixinServerLevelSaveContinuation} (which catches only
 * {@link ChunkSaveBarrierException}) skipped entity saves and later dimensions;
 * an unbounded {@code join()} could also block forever.
 *
 * <p>This test disassembles the compiled production mixin, so it fails on the
 * predecessor bytes and passes only when both bounded fences exist. It does not
 * load the mixin or apply it in Forge; the packaged runtime gate is separate
 * evidence.
 */
@Timeout(60)
class FirstStorageFenceContractTest {

    private static final String MIXIN = "build/classes/java/main/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.class";
    private static final String SUPER_FLUSH = "ChunkStorage.flushWorker:()V";
    private static final String SYNC = "IOWorker.synchronize:(Z)Ljava/util/concurrent/CompletableFuture;";
    private static final String FENCE = "SaveBarrier.awaitStorageFence:";
    private static final String RETRY = "SaveBarrier.awaitQuietAndRetryUntil:";
    private static final String JOIN = "joinSaveFutures:()V";

    @Test
    void flushWorkerUsesTwoBoundedFencesAndNoUnboundedSuperFlush() throws Exception {
        String flush = extractMethod(javap(Path.of(MIXIN)), "public void flushWorker();");

        assertFalse(flush.contains(SUPER_FLUSH),
                "flushWorker must not call ChunkStorage.flushWorker (unbounded join leaks CompletionException): " + flush);
        assertEquals(2, occurrences(flush, FENCE),
                "flushWorker must wait on two bounded storage fences (first + post-retry): " + flush);
        assertEquals(2, occurrences(flush, SYNC),
                "flushWorker must invoke synchronize(true) inline for each bounded fence: " + flush);
        assertEquals(2, occurrences(flush, JOIN),
                "flushWorker must join in-flight stores before and after the first fence: " + flush);

        int join1 = flush.indexOf(JOIN);
        int sync1 = flush.indexOf(SYNC);
        int fence1 = flush.indexOf(FENCE);
        int join2 = flush.indexOf(JOIN, join1 + 1);
        int retry = flush.indexOf(RETRY);
        int sync2 = flush.indexOf(SYNC, sync1 + 1);
        int fence2 = flush.indexOf(FENCE, fence1 + 1);
        assertTrue(join1 >= 0, "first joinSaveFutures missing: " + flush);
        assertTrue(sync1 > join1 && fence1 > sync1,
                "the first synchronize(true) must be waited on by the first bounded fence: " + flush);
        assertTrue(join2 > fence1, "second joinSaveFutures must follow the first fence: " + flush);
        assertTrue(retry > join2, "the retry barrier must follow the second join: " + flush);
        assertTrue(sync2 > retry && fence2 > sync2,
                "the post-retry synchronize(true) must be waited on by the second bounded fence: " + flush);
        assertTrue(flush.contains("threadedHorizons$failedSaves"), flush);
    }

    @Test
    void storageFenceTranslatesFailureTimeoutAndExhaustion() {
        long deadline = SaveBarrier.deadlineAfter(5_000L);

        SaveBarrier.awaitStorageFence(CompletableFuture.completedFuture(null), deadline);

        ChunkSaveBarrierException failed = assertThrows(ChunkSaveBarrierException.class,
                () -> SaveBarrier.awaitStorageFence(
                        CompletableFuture.failedFuture(new RuntimeException("force failed")), deadline));
        assertNotNull(failed.getCause(), "the fence must keep the storage failure as its cause");

        assertThrows(ChunkSaveBarrierException.class,
                () -> SaveBarrier.awaitStorageFence(null, deadline));
        assertThrows(ChunkSaveBarrierException.class,
                () -> SaveBarrier.awaitStorageFence(CompletableFuture.completedFuture(null),
                        SaveBarrier.deadlineAfter(-1L)));
        ChunkSaveBarrierException timedOut = assertThrows(ChunkSaveBarrierException.class,
                () -> SaveBarrier.awaitStorageFence(new CompletableFuture<>(), SaveBarrier.deadlineAfter(150L)));
        assertTrue(timedOut.getCause() instanceof java.util.concurrent.TimeoutException,
                String.valueOf(timedOut.getCause()));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int cursor = 0;
        while (cursor < haystack.length()) {
            int found = haystack.indexOf(needle, cursor);
            if (found < 0) {
                break;
            }
            count++;
            cursor = found + needle.length();
        }
        return count;
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

    private static String javap(Path classFile) throws Exception {
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
