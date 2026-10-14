package network.vonix.threadedhorizons.ci;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repo-wide Mixin field-validation gate.
 *
 * <p>Mixin validates the fields of every {@code @Mixin} class before applying
 * it. A {@code static} field that is <em>declared by the mixin</em> (i.e. not a
 * {@code @Shadow} of an existing target field) and is not {@code private} is a
 * hard {@code InvalidMixinException} at apply time. Because apply happens lazily
 * when the target class first loads, the failure surfaces during server startup
 * or world load as:
 *
 * <pre>
 * Caused by: InvalidMixinException: Mixin &lt;config&gt;:&lt;Class&gt;
 *   contains non-private static field &lt;NAME&gt;:&lt;DESC&gt;
 * </pre>
 *
 * <p>This shipped once. 1.1.0 added
 * {@code public static final boolean DURABLE_WRITES_FORCED = true;} to
 * {@code MixinRegionBasedStorage}. It compiled cleanly, passed the full unit
 * suite including a bytecode inspection of that exact class, and then crashed
 * Isle of Berk on boot with the error above.
 *
 * <p>A clean compile and a bytecode check cannot detect this rule, because it is
 * Mixin's own preprocessor validation rather than a JVM or Java-language
 * constraint. This gate is the offline equivalent, and it covers every mixin in
 * the tree rather than only the one that broke.
 *
 * <p>Correctly-annotated {@code @Shadow} fields are exempt: they declare no new
 * field, they bind to one the target already has. The annotation may sit on the
 * line above the declaration, so the check looks at the previous non-empty,
 * non-annotation content line as well as the declaration line itself.
 */
class MixinFieldValidationGateTest {

    private static final Path MIXIN_ROOT = Path.of("src/main/java/network/vonix/threadedhorizons/mixin");

    @Test
    void noMixinDeclaresANonPrivateStaticField() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> mixinFiles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(MIXIN_ROOT)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(mixinFiles::add);
        }

        assertTrue(!mixinFiles.isEmpty(), "no mixin sources found under " + MIXIN_ROOT);

        for (Path file : mixinFiles) {
            String source = Files.readString(file);
            if (!source.contains("@Mixin(")) {
                continue;
            }
            // Comments removed so prose in Javadoc can never trip the gate.
            String[] lines = stripComments(source).split("\n");
            // A run of annotation lines immediately above a declaration counts as
            // annotation for it. @Shadow and @Final conventionally sit on separate
            // lines, so the flag must survive other annotations, not be cleared by
            // them, and must reset on the first non-annotation line.
            boolean pendingShadow = false;

            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();

                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("@")) {
                    if (trimmed.contains("@Shadow")) {
                        pendingShadow = true;
                    }
                    continue;
                }
                boolean isShadow = pendingShadow;
                pendingShadow = false;

                if (!trimmed.contains(" static ")) {
                    continue;
                }
                if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                    continue;
                }
                // Only a field declaration is of interest: a type and a name,
                // optionally initialised, and no parameter list.
                boolean looksLikeField = trimmed.matches(
                        ".*\\bstatic\\b[^=()]*[A-Za-z_$][A-Za-z0-9_$]*\\s*(=.*)?;.*");
                if (!looksLikeField) {
                    continue;
                }
                // @Shadow fields bind to existing target fields; Mixin does not
                // validate their modifiers and they are not declarations.
                if (isShadow) {
                    continue;
                }
                if (trimmed.contains("private static")) {
                    continue;
                }
                violations.add(file + ":" + (i + 1) + " -> " + trimmed);
            }
        }

        assertTrue(violations.isEmpty(),
                "Mixin rejects non-private static fields at apply time and the server "
                        + "will crash on boot. Violations:\n" + String.join("\n", violations));
    }

    /**
     * The exact 1.1.0 regression, asserted directly so it cannot return.
     * Comments are stripped, so prose describing the incident is still allowed.
     */
    @Test
    void theOnePointOneZeroRegressionFieldIsNotDeclared() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            List<Path> files = new ArrayList<>();
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            for (Path file : files) {
                String code = stripComments(Files.readString(file));
                if (code.contains("DURABLE_WRITES_FORCED")) {
                    hits.add(file.toString());
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "DURABLE_WRITES_FORCED was declared and broke the 1.1.0 boot; "
                        + "it must not appear in executable code: " + hits);
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
