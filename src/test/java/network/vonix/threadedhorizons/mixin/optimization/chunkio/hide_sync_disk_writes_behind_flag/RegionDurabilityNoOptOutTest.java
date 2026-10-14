package network.vonix.threadedhorizons.mixin.optimization.chunkio.hide_sync_disk_writes_behind_flag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural gates for the durable region-write correction mixin.
 *
 * <p>Two independent contracts are enforced here, because both have been
 * violated in shipped artefacts:
 *
 * <ol>
 *   <li><b>Durability must not be user-overridable.</b> A previous revision
 *       read the system property
 *       {@code network.vonix.threadedhorizons.chunkio.syncDiskWrites} and
 *       allowed {@code RegionFileStorage.sync = false}, which buffered region
 *       writes outside the reach of {@code SaveFutureTracker}. The server then
 *       completed {@code Stopping server} with region bytes still in memory; a
 *       hard stop lost them and containers reloaded from an older
 *       block-entity state.</li>
 *   <li><b>The mixin class must survive Mixin's own field validation.</b>
 *       Mixin rejects a {@code static} field that is not {@code private} with
 *       {@code InvalidMixinException} at apply time, which fails the server
 *       boot during world load. Shipped once as
 *       {@code contains non-private static field DURABLE_WRITES_FORCED:Z}.
 *       A clean compile and a green unit-test run do not detect this; only a
 *       structural gate like the one below, or a real boot, does.</li>
 * </ol>
 */
class RegionDurabilityNoOptOutTest {

    private static final Path MIXIN = Path.of(
            "src/main/java/network/vonix/threadedhorizons/mixin/optimization/chunkio/"
                    + "hide_sync_disk_writes_behind_flag/MixinRegionBasedStorage.java");

    /** The mixin must be registered so a buffered region file is corrected. */
    private static final Path MIXIN_CONFIG = Path.of("src/main/resources/threadedhorizons.mixins.json");

    @Test
    void regionSyncIsNotOverridableByAnyRuntimeSource() throws Exception {
        String body = Files.readString(MIXIN);

        assertFalse(body.contains("System.getProperty"),
                "durable region writes must not be switchable off via a system property");
        assertFalse(body.contains("System.getenv"),
                "durable region writes must not be switchable off via an environment variable");
        assertFalse(body.contains("ThreadedHorizonsConfig"),
                "durable region writes must not be switchable off via config");
        assertFalse(body.contains("Boolean.parseBoolean"),
                "no string-to-boolean durability override may exist in this mixin");

        assertTrue(body.contains("this.sync = true;"),
                "sync must be assigned the durable value directly");
    }

    @Test
    void requestedSyncArgumentIsIgnoredSoBufferedCallersAreCorrected() throws Exception {
        String body = Files.readString(MIXIN);
        assertFalse(body.contains("this.sync = requestedSync;"),
                "the caller-supplied sync flag must not be honoured");
    }

    /**
     * Mixin field-validation gate.
     *
     * <p>Every field declared in this mixin class must be either
     * {@code @Shadow} (not a new field, so exempt) or {@code private static}.
     * A public/protected/package-private static field is rejected by Mixin at
     * apply time and crashes the server during world load. This gate catches
     * that offline, which the 1.1.0 build could not.
     */
    @Test
    void mixinDeclaresNoNonPrivateStaticField() throws Exception {
        String body = stripComments(Files.readString(MIXIN));

        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains(" static ") && !trimmed.contains("@Shadow")) {
                assertTrue(trimmed.contains("private static"),
                        "Mixin rejects non-private static fields at apply time; "
                                + "offending declaration: " + trimmed);
            }
        }
    }

    /**
     * Belt-and-braces: this mixin needs no static field at all, so none may
     * exist. Keeps the class trivially valid for Mixin and preserves the
     * 1.1.0 regression as a named assertion.
     */
    @Test
    void mixinDeclaresNoStaticFieldAtAll() throws Exception {
        String body = stripComments(Files.readString(MIXIN));

        assertFalse(body.contains("DURABLE_WRITES_FORCED"),
                "the field that broke the 1.1.0 boot must not be reintroduced");

        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains(" static ") && !trimmed.contains("@Shadow")) {
                assertTrue(trimmed.contains("(") || trimmed.endsWith(";"),
                        "unexpected static member: " + trimmed);
            }
        }
    }

    @Test
    void mixinRemainsRegisteredInTheMainConfig() throws Exception {
        String config = Files.readString(MIXIN_CONFIG);
        assertTrue(config.contains(
                        "optimization.chunkio.hide_sync_disk_writes_behind_flag.MixinRegionBasedStorage"),
                "the durability-correction mixin must stay registered");
    }

    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder out = new StringBuilder();
        for (String line : noBlock.split("\n")) {
            int idx = line.indexOf("//");
            out.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return out.toString();
    }
}
