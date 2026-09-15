package network.vonix.threadedhorizons.ci;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductIdentityContractTest {

    @Test
    void modsTomlDeclaresReplacementIdentityAndForge401312() throws Exception {
        String toml = Files.readString(Path.of("src/main/resources/META-INF/mods.toml"));
        assertTrue(toml.contains("modId=\"threadedhorizons\""));
        assertTrue(toml.contains("versionRange=\"[40.3.12,41)\""));
        assertFalse(toml.contains("versionRange=\"[40.3.11,41)\""));
        assertTrue(toml.contains("standalone Minecraft 1.18.2 Forge implementation intended to replace C2ME"));
        assertTrue(toml.contains("does not bundle a C2ME artifact"));
        assertTrue(toml.contains("Do not install it alongside a C2ME-branded artifact"));
        assertFalse(toml.contains("does not replace C2ME"));
    }

    @Test
    void readmeAndLedgerPresentStandaloneReplacementWithoutParityClaim() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertTrue(readme.contains("standalone Forge 1.18.2 implementation intended to replace C2ME"));
        assertTrue(readme.contains("threadedhorizons"));
        assertTrue(readme.contains("1.0.7"));
        assertTrue(readme.contains("40.3.12"));
        assertTrue(readme.contains("Do not install it alongside a C2ME-branded artifact"));
        assertFalse(readme.contains("does not replace C2ME"));
        assertFalse(readme.contains("1.0.1 is available"));
        assertFalse(readme.contains("[40.3.11,41)"));

        String ledger = Files.readString(Path.of("docs/feature-ledger.md"));
        assertTrue(ledger.contains("standalone Forge implementation intended to replace C2ME"));
        assertTrue(ledger.contains("intentionally_excluded"));
        assertTrue(ledger.contains("optimizeAsyncChunkRequest"));
        assertTrue(ledger.contains("Global biome cache"));
        assertTrue(ledger.contains("Vector acceleration"));
        assertTrue(ledger.contains("Fabric companions"));
        assertTrue(ledger.contains("not a 1:1 C2ME parity claim")
                || ledger.contains("not a 1:1 parity claim"));
        assertFalse(ledger.contains("claims 1:1 C2ME parity"));
    }

    @Test
    void gradleIdentityIs107AndForge401312() throws Exception {
        String props = Files.readString(Path.of("gradle.properties"));
        assertTrue(props.contains("mod_version=1.0.7"));
        assertTrue(props.contains("forge_version=40.3.12"));
        assertTrue(props.contains("minecraft_version=1.18.2"));
    }
}
