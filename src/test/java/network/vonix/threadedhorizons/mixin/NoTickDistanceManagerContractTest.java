package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoTickDistanceManagerContractTest {

    @Test
    void noTickMixinDoesNotReplaceVanillaTickingTracker() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/notickvd/MixinChunkTicketManager.java"));
        assertFalse(body.contains("@Mutable"), body);
        assertFalse(body.contains("tickingTicketsTracker = new NoOPTickingMap"), body);
        assertFalse(body.contains("tickingTicketsTracker"), body);
        assertTrue(body.contains("noTickSystem"), body);
    }

    @Test
    void entityFilteringAvoidsWrapperWhenSnapshotIsEmpty() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/notickvd/MixinServerChunkManager.java"));
        assertTrue(body.contains("noTickOnlyChunks.isEmpty()"), body);
    }
}