package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Official Forge 1.18.2 save callers, plus the compiled continuation mixins.
 * This does not apply the mixins in a Forge runtime.
 */
class SaveContinuationBytecodeTest {

    private static final Pattern RANGE = Pattern.compile("(?m)^\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+");

    @Test
    void officialSaveHasNoHandlerBetweenChunksAndEntitiesOrLaterDimensions() throws Exception {
        String level = javapOfficial("net.minecraft.server.level.ServerLevel", true);
        String save = extractMethod(level, "public void save(net.minecraft.util.ProgressListener, boolean, boolean);");
        int chunks = save.indexOf("ServerChunkCache.save:(Z)V");
        int entities = save.indexOf("PersistentEntitySectionManager.saveAll:()V");
        int auto = save.indexOf("PersistentEntitySectionManager.autoSave:()V");
        assertTrue(chunks >= 0 && entities > chunks && auto > entities, save);
        assertFalse(save.contains("Exception table"), save);

        String server = javapOfficial("net.minecraft.server.MinecraftServer", true);
        String all = extractMethod(server, "public boolean saveAllChunks(boolean, boolean, boolean);");
        int levelSave = all.indexOf("ServerLevel.save:(Lnet/minecraft/util/ProgressListener;ZZ)V");
        int success = all.indexOf("ThreadedAnvilChunkStorage ({}): All chunks are saved");
        int allDimensions = all.indexOf("ThreadedAnvilChunkStorage: All dimensions are saved");
        assertTrue(levelSave >= 0 && success > levelSave && allDimensions > success, all);
        assertFalse(all.contains("Exception table"), all);

        String stop = extractMethod(server, "public void stopServer();");
        int saveAll = lineOffset(stop, "saveAllChunks:(ZZZ)Z");
        int close = lineOffset(stop, "ServerLevel.close:()V");
        assertTrue(saveAll >= 0 && close > saveAll, stop);
        int tableAt = stop.indexOf("Exception table:");
        int tableEnd = stop.indexOf("StackMapTable:", tableAt);
        assertTrue(tableAt >= 0 && tableEnd > tableAt, stop);
        String table = stop.substring(tableAt, tableEnd);
        assertTrue(table.contains("250") && table.contains("271"), table);
        Matcher ranges = RANGE.matcher(table);
        boolean covered = false;
        while (ranges.find()) {
            int from = Integer.parseInt(ranges.group(1));
            int to = Integer.parseInt(ranges.group(2));
            if (saveAll >= from && saveAll < to) {
                covered = true;
            }
        }
        assertFalse(covered, "saveAllChunks's invoke sits inside a stopServer exception handler:\n" + table);
    }

    @Test
    void compiledMixinsContinueEntitySavesAndWithholdSuccess() throws Exception {
        String levelMixin = javapClass(Path.of(
                "build/classes/java/main/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinServerLevelSaveContinuation.class"));
        assertTrue(levelMixin.contains("save(Lnet/minecraft/util/ProgressListener;ZZ)V"), levelMixin);
        assertTrue(levelMixin.contains("Lnet/minecraft/server/level/ServerChunkCache;save(Z)V")
                || levelMixin.contains("ServerChunkCache.save:(Z)V"), levelMixin);
        assertTrue(levelMixin.contains("SaveFailureSignal.beginLevel:()V"), levelMixin);
        String handler = extractMethod(levelMixin, "private void threadedHorizons$saveChunksAndContinue");
        assertTrue(handler.contains("ServerChunkCache.save:(Z)V"), handler);
        assertTrue(handler.contains("SaveFailureSignal.recordIfBarrier:"), handler);
        assertTrue(handler.contains("ChunkSaveBarrierException"), handler);
        assertFalse(handler.contains("java/lang/IllegalStateException"), handler);
        assertFalse(handler.contains("athrow"), handler);
        assertFalse(handler.contains("saveAll:()V"), handler);

        String serverMixin = javapClass(Path.of(
                "build/classes/java/main/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinMinecraftServerSaveReport.class"));
        assertTrue(serverMixin.contains("saveAllChunks(ZZZ)Z"), serverMixin);
        assertTrue(serverMixin.contains("SaveFailureSignal.beginPass:()V"), serverMixin);
        assertTrue(serverMixin.contains("SaveFailureSignal.endPass:()V"), serverMixin);
        assertTrue(serverMixin.contains("Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V"), serverMixin);
        assertTrue(serverMixin.contains("Lorg/slf4j/Logger;info(Ljava/lang/String;)V"), serverMixin);
        assertTrue(serverMixin.contains("success is withheld"), serverMixin);
        assertTrue(serverMixin.contains("not all dimensions saved"), serverMixin);
        assertTrue(serverMixin.contains("Logger.error:"), serverMixin);
        assertTrue(serverMixin.contains("SaveFailureSignal.pollLoggedLevel:()"), serverMixin);
        assertTrue(serverMixin.contains("SaveFailureSignal.aggregateFailure:()"), serverMixin);
        assertTrue(serverMixin.contains("SaveFailureSignal.effectiveSaveSucceeded:(Z)Z"), serverMixin);
        String endPass = extractMethod(serverMixin, "private void threadedHorizons$endSavePass");
        int decision = endPass.indexOf("effectiveSaveSucceeded:(Z)Z");
        int forceFalse = endPass.indexOf("setReturnValue:(Ljava/lang/Object;)V");
        int clear = endPass.indexOf("SaveFailureSignal.endPass:()V");
        assertTrue(decision >= 0 && forceFalse > decision && clear > decision, endPass);
        assertTrue(endPass.contains("iconst_0"), endPass);
        String json = Files.readString(Path.of("src/main/resources/threadedhorizons.mixins.json"));
        assertTrue(json.contains("threading.chunkio.MixinServerLevelSaveContinuation"), json);
        assertTrue(json.contains("threading.chunkio.MixinMinecraftServerSaveReport"), json);
    }

    private static int lineOffset(String disassembly, String needle) {
        for (String line : disassembly.split("\n")) {
            if (line.contains(needle)) {
                String trimmed = line.stripLeading();
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    return Integer.parseInt(trimmed.substring(0, colon).trim());
                }
            }
        }
        return -1;
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

    private static String javapOfficial(String binaryName, boolean verbose) throws Exception {
        Path jar = Files.walk(Path.of("build/fg_cache"))
                .filter(path -> path.getFileName().toString().equals("forge-1.18.2-40.3.12_mapped_official_1.18.2.jar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mapped forge jar missing"));
        List<String> args = new ArrayList<>();
        if (verbose) {
            args.add("-v");
        }
        args.add("-c");
        args.add("-p");
        args.add("-classpath");
        args.add(jar.toString());
        args.add(binaryName);
        return runJavap(args);
    }

    private static String javapClass(Path classFile) throws Exception {
        assertTrue(classFile.toFile().isFile(), classFile.toString());
        return runJavap(List.of("-v", "-c", "-p", classFile.toString()));
    }

    private static String runJavap(List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "javap").toString());
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            fail("javap failed: " + output);
        }
        return output;
    }
}
