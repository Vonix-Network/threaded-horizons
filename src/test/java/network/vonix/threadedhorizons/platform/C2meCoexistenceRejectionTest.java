package network.vonix.threadedhorizons.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C2meCoexistenceRejectionTest {

    @Test
    void c2meModIdIsRejectedAndAbsenceIsAllowed() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LoaderHooks.rejectUnsupportedC2meCoexistence(true));
        String message = thrown.getMessage();
        assertTrue(message.contains("threadedhorizons"));
        assertTrue(message.contains("C2ME"));
        assertTrue(message.toLowerCase().contains("overlapping mixins"));
        assertDoesNotThrow(() -> LoaderHooks.rejectUnsupportedC2meCoexistence(false));
        assertFalse(LoaderHooks.isC2meBrandedLoaded(), "unit tests have no c2me mod on the classpath");
    }

    @Test
    void modInitializerCallsCoexistenceGuard() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/ThreadedHorizonsMod.java"));
        assertTrue(body.contains("LoaderHooks.rejectUnsupportedC2meCoexistence()"));
        assertTrue(body.contains("MOD_ID = \"threadedhorizons\""));
    }
}
