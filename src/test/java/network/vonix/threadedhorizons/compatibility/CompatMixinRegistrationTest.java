package network.vonix.threadedhorizons.compatibility;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CompatMixinRegistrationTest {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Test
    void isleOfBerkAndVariantLoaderModulesArePackagedAndHaveInjectors() throws Exception {
        assertModule(
                "src/main/resources/threadedhorizons-compat.iobvariantloader.mixins.json",
                "src/main/java/network/vonix/threadedhorizons/compatibility/mixin/iobvariantloader",
                List.of("MixinSurfaceRestriction", "MixinDragonVariantSpawnerUtil")
        );
        assertModule(
                "src/main/resources/threadedhorizons-compat.isleofberk.mixins.json",
                "src/main/java/network/vonix/threadedhorizons/compatibility/mixin/isleofberk",
                List.of("MixinADragonBaseIsWaterBelow")
        );
    }

    @Test
    void compatibilityModuleEnablesLiveIoBMods() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/compatibility/mixin/ThreadedHorizonsCompatibilityModule.java"));
        assertTrue(body.contains("addMixins(\"iobvariantloader\""));
        assertTrue(body.contains("addMixins(\"isleofberk\""));
        assertFalse(body.contains("//        addMixins(\"iobvariantloader\""));
    }

    private static void assertModule(String jsonPath, String sourceRoot, List<String> expected) throws Exception {
        String json = Files.readString(Path.of(jsonPath));
        List<String> mixins = section(json, "\"mixins\"");
        List<String> missing = new ArrayList<>();
        for (String mixin : expected) {
            if (!mixins.contains(mixin)) {
                missing.add(mixin);
            }
            Path source = Path.of(sourceRoot, mixin + ".java");
            if (!Files.isRegularFile(source)) {
                missing.add(mixin + ".java");
                continue;
            }
            String body = Files.readString(source);
            assertTrue(body.contains("@Pseudo"), mixin);
            assertTrue(body.contains("@Redirect") || body.contains("@Inject"), mixin);
            assertFalse(body.contains("com.GACMD.isleofberk") && body.contains("import com.GACMD"),
                    "ARR IoB types must not be imported");
        }
        if (!missing.isEmpty()) {
            fail("missing=" + missing + " listed=" + mixins);
        }
        assertTrue(json.contains("\"defaultRequire\": 0"));
        assertTrue(json.contains("\"required\": false"));
    }

    private static List<String> section(String json, String key) {
        int keyAt = json.indexOf(key);
        if (keyAt < 0) {
            return List.of();
        }
        int open = json.indexOf('[', keyAt);
        int close = json.indexOf(']', open);
        Matcher matcher = QUOTED.matcher(json.substring(open, close + 1));
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
