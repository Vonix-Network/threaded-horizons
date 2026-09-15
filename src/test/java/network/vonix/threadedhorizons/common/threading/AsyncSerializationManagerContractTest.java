package network.vonix.threadedhorizons.common.threading;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncSerializationManagerContractTest {

    @Test
    void scopeCopiesMutableBlockEntityPositionSet() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/threading/chunkio/AsyncSerializationManager.java"));
        assertTrue(body.contains("new HashSet<>(chunk.getBlockEntitiesPos())"), body);
    }

    @Test
    void lightingViewCopiesLayersLazilyInsteadOfEagerSectionMaps() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/threading/chunkio/AsyncSerializationManager.java"));
        assertTrue(body.contains("class CachedLightingView"), body);
        assertTrue(body.contains("private final DataLayer[] cached"), body);
        assertFalse(body.contains("Map<SectionPos, DataLayer>"),
                "lighting must not retain a SectionPos-keyed map of every section");
        assertFalse(body.contains("Collectors.toMap"), body);
        int ctor = body.indexOf("CachedLightingView(LevelLightEngine provider");
        assertTrue(ctor >= 0, body);
        String constructor = body.substring(ctor, body.indexOf("public LightLayer getLightType()", ctor));
        assertFalse(constructor.contains("lighting.copy()"), constructor);
        assertTrue(body.contains("lighting.copy()"), body);
    }

    @Test
    void productionChunkSaveActuallyPushesTheSerializationSnapshotScope() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        assertTrue(body.contains("new AsyncSerializationManager.Scope"), body);
        assertTrue(body.contains("AsyncSerializationManager.push"), body);
        assertTrue(body.contains("AsyncSerializationManager.pop"), body);
    }

    @Test
    void chunkSaveProfilerPopOnlyRunsAfterSuccessfulPush() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java"));
        int save = body.indexOf("private boolean save(ChunkAccess chunk)");
        assertTrue(save >= 0, body);
        String method = body.substring(save);
        int nextMember = method.indexOf("\n    @Inject");
        if (nextMember > 0) {
            method = method.substring(0, nextMember);
        }
        assertTrue(method.contains("profilerPushed"), method);
        assertTrue(method.contains("if (profilerPushed)"), method);
        int push = method.indexOf("getProfiler().push(\"chunkSave\")");
        int flag = method.indexOf("profilerPushed = true");
        int guardedPop = method.indexOf("if (profilerPushed)");
        assertTrue(push >= 0, method);
        assertTrue(flag > push, method);
        assertTrue(guardedPop > flag, method);
    }
}