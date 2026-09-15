package network.vonix.threadedhorizons.ci;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TH-CI-ARTIFACT-004: CI workflow artifact path must track gradle.properties
 * (threaded-horizons / current MC / current mod_version), not a stale pin.
 */
class CiArtifactNameConsistencyTest {

    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();
    private static final Path GRADLE_PROPERTIES = ROOT.resolve("gradle.properties");
    private static final Path WORKFLOW = ROOT.resolve(".github/workflows/build.yml");
    private static final Path BUILD_GRADLE = ROOT.resolve("build.gradle");

    @Test
    void workflowArtifactMatchesGradlePropertiesAndBuildConvention() throws Exception {
        Map<String, String> props = loadProperties(GRADLE_PROPERTIES);
        String mc = require(props, "minecraft_version");
        String ver = require(props, "mod_version");
        String base = require(props, "archives_base_name");

        String buildGradle = Files.readString(BUILD_GRADLE);
        assertTrue(buildGradle.contains("archivesBaseName = \"${project.archives_base_name}-mc${project.minecraft_version}\""),
                "build.gradle archivesBaseName convention drifted");

        String expectedJar = base + "-mc" + mc + "-" + ver + ".jar";
        String expectedPath = "build/libs/" + expectedJar;
        assertEquals("threaded-horizons-mc" + mc + "-" + ver + ".jar", expectedJar);

        String workflow = Files.readString(WORKFLOW);
        assertTrue(workflow.contains("archives_base_name"),
                "workflow must derive artifact from gradle.properties");
        assertTrue(workflow.contains("mod_version"),
                "workflow must read mod_version from gradle.properties");
        assertTrue(workflow.contains("minecraft_version"),
                "workflow must read minecraft_version from gradle.properties");
        assertTrue(workflow.contains("steps.artifact.outputs.expected_path"),
                "upload/check steps must use resolved expected_path output");

        // Hard-coded upload/test paths must not pin a different version than gradle.properties.
        Pattern hardCodedJar = Pattern.compile("build/libs/" + Pattern.quote(base) + "-mc[^\\s'\"]+\\.jar");
        Matcher matcher = hardCodedJar.matcher(workflow);
        while (matcher.find()) {
            String pinned = matcher.group();
            assertEquals(expectedPath, pinned,
                    "workflow hard-codes jar path that does not match gradle.properties: " + pinned);
        }

        // Soft check: if a jar already exists from a local build, it must match.
        Path libs = ROOT.resolve("build/libs");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> stream = Files.list(libs)) {
                boolean foundExpected = stream
                        .map(path -> path.getFileName().toString())
                        .anyMatch(name -> name.equals(expectedJar));
                boolean anyJar;
                try (Stream<Path> again = Files.list(libs)) {
                    anyJar = again.anyMatch(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".jar") && !name.endsWith("-sources.jar");
                    });
                }
                if (anyJar) {
                    assertTrue(foundExpected,
                            "build/libs has jars but missing expected " + expectedPath);
                }
            }
        }

        // Current known product identity for this candidate lane.
        assertFalse(ver.equals("0.1.0-alpha.1"), "mod_version must not remain on retired alpha.1");
        assertEquals("1.0.7", ver, "candidate version is 1.0.7");
        assertTrue(mc.equals("1.18.2"), "candidate lane is Forge 1.18.2");
        assertTrue(base.equals("threaded-horizons"), "archives_base_name");
        assertEquals("40.3.12", require(props, "forge_version"));
    }

    private static String require(Map<String, String> props, String key) {
        String value = props.get(key);
        assertTrue(value != null && !value.isBlank(), key);
        return value;
    }

    private static Map<String, String> loadProperties(Path path) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        Pattern line = Pattern.compile("^([A-Za-z0-9_.-]+)=(.*)$");
        for (String raw : Files.readAllLines(path)) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher matcher = line.matcher(trimmed);
            if (matcher.matches()) {
                map.put(matcher.group(1), matcher.group(2).trim());
            }
        }
        return map;
    }
}
