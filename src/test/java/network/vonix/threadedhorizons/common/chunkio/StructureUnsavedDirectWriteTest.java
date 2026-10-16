package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Official Forge 1.18.2 {@code ChunkAccess} structure-start and structure-reference
 * methods put {@code unsaved} directly. They do not call {@code setUnsaved}, so the
 * {@code setUnsaved} generation hook never sees them. The compiled mixin must name
 * those shipped methods and the shipped {@code ChunkAccess.unsaved} field.
 */
class StructureUnsavedDirectWriteTest {

    private static final String FIELD = "Lnet/minecraft/world/level/chunk/ChunkAccess;unsaved:Z";
    /** Official {@code javap -c} method headers. Descriptors are not printed there. */
    private static final List<String> OFFICIAL_HEADERS = List.of(
            "public void setStartForFeature(net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature<?, ?>, net.minecraft.world.level.levelgen.structure.StructureStart);",
            "public void setAllStarts(java.util.Map<net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature<?, ?>, net.minecraft.world.level.levelgen.structure.StructureStart>);",
            "public void addReferenceForFeature(net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature<?, ?>, long);",
            "public void setAllReferences(java.util.Map<net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature<?, ?>, it.unimi.dsi.fastutil.longs.LongSet>);"
    );
    /** Descriptor strings Mixin stores on the injector. These must match the shipped methods. */
    private static final List<String> SHIPPED_DESCRIPTORS = List.of(
            "setStartForFeature(Lnet/minecraft/world/level/levelgen/feature/ConfiguredStructureFeature;Lnet/minecraft/world/level/levelgen/structure/StructureStart;)V",
            "setAllStarts(Ljava/util/Map;)V",
            "addReferenceForFeature(Lnet/minecraft/world/level/levelgen/feature/ConfiguredStructureFeature;J)V",
            "setAllReferences(Ljava/util/Map;)V"
    );

    @Test
    void structurePutfieldsAdvanceGenerationOnTheShippedField() throws Exception {
        String chunkAccess = javapOfficial("net.minecraft.world.level.chunk.ChunkAccess");
        for (String header : OFFICIAL_HEADERS) {
            String bodyExact = extractMethod(chunkAccess, header);
            assertTrue(bodyExact.contains("putfield") && bodyExact.contains("unsaved:Z"), bodyExact);
            assertTrue(bodyExact.contains("iconst_1"), bodyExact);
            assertTrue(!bodyExact.contains("setUnsaved"), header + " must not go through setUnsaved: " + bodyExact);
        }
        String setUnsaved = extractMethod(chunkAccess, "public void setUnsaved(boolean);");
        assertTrue(setUnsaved.contains("putfield") && setUnsaved.contains("unsaved:Z"), setUnsaved);
        String light = extractMethod(chunkAccess, "public void setLightCorrect(boolean);");
        assertTrue(light.contains("setUnsaved:(Z)V"), light);

        Path mixinClass = Path.of(
                "build/classes/java/main/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinChunkAccessStructureGenerations.class");
        assertTrue(mixinClass.toFile().isFile(),
                "no compiled hook targets the four ChunkAccess structure putfields of unsaved; P3 only hooks setUnsaved and LevelChunk.unsaved");
        String mixin = javapVerbose(mixinClass);
        assertTrue(mixin.contains(FIELD), mixin);
        assertTrue(mixin.contains("opcode=181"), mixin);
        assertTrue(mixin.contains("value=\"FIELD\""), mixin);
        for (String method : SHIPPED_DESCRIPTORS) {
            assertTrue(mixin.contains(method), "mixin annotation misses shipped method " + method + "\n" + mixin);
        }
        String handler = extractMethod(mixin, "private void onStructureUnsavedAdvanceGeneration");
        assertTrue(handler.contains("DirtyChunkGenerations.markMutated:"), handler);
        String json = Files.readString(Path.of("src/main/resources/threadedhorizons.mixins.json"));
        assertTrue(json.contains("threading.chunkio.MixinChunkAccessStructureGenerations"), json);

        String setUnsavedHook = javapVerbose(Path.of(
                "build/classes/java/main/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinChunkDirtyGenerations.class"));
        assertTrue(setUnsavedHook.contains("method=[\"setUnsaved\"]") || setUnsavedHook.contains("setUnsaved"), setUnsavedHook);
        assertTrue(setUnsavedHook.contains("DirtyChunkGenerations.markMutated:"), setUnsavedHook);
        String levelChunkHook = javapVerbose(Path.of(
                "build/classes/java/main/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinWorldChunkDirtyGenerations.class"));
        assertTrue(levelChunkHook.contains("Lnet/minecraft/world/level/chunk/LevelChunk;unsaved:Z"), levelChunkHook);
        assertTrue(levelChunkHook.contains("setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"), levelChunkHook);
        assertTrue(levelChunkHook.contains("<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V"), levelChunkHook);

        String levelChunk = javapOfficial("net.minecraft.world.level.chunk.LevelChunk");
        assertTrue(levelChunk.contains("putfield") && levelChunk.contains("unsaved:Z"), levelChunk);
        for (String untouched : List.of(
                "net.minecraft.world.level.chunk.ProtoChunk",
                "net.minecraft.world.level.chunk.ImposterProtoChunk")) {
            String disassembly = javapOfficial(untouched);
            assertTrue(!disassembly.contains("unsaved:Z"), untouched + " writes unsaved directly:\n" + disassembly);
        }
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

    private static String javapOfficial(String binaryName) throws Exception {
        Path jar = Files.walk(Path.of("build/fg_cache"))
                .filter(path -> path.getFileName().toString().equals("forge-1.18.2-40.3.12_mapped_official_1.18.2.jar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mapped forge jar missing"));
        return runJavap(List.of("-c", "-p", "-classpath", jar.toString(), binaryName));
    }

    private static String javapVerbose(Path classFile) throws Exception {
        return runJavap(List.of("-v", "-p", classFile.toString()));
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
