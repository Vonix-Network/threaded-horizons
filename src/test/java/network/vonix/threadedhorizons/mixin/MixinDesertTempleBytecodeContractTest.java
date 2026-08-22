package network.vonix.threadedhorizons.mixin;

import net.minecraft.world.level.levelgen.structure.DesertPyramidPiece;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.points.BeforeFieldAccess;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinDesertTempleBytecodeContractTest {

    private static final String OWNER = "net/minecraft/world/level/levelgen/structure/DesertPyramidPiece";

    @Test
    void desertMixinPinsTheNarrowArraySetSearchWindow() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/mixin/fixes/worldgen/threading/MixinDesertTempleGenerator.java"));
        assertTrue(source.contains("args = {\"array=set\", \"fuzz=14\"}"));
        assertFalse(source.contains("fuzz=32"), "the redirect must not use the broad maximum search window");
        assertTrue(source.contains("array[index] = value"),
                "SET redirect must write through to the vanilla boolean[] so RETURN recopy cannot wipe flags");
    }

    @Test
    void mixinArraySetSelectionMatchesExactlyTheHasPlacedChestStore() throws Exception {
        MethodNode postProcess = postProcessMethod();
        List<FieldInsnNode> accesses = hasPlacedChestGets(postProcess);
        assertTrue(accesses.size() >= 2, "postProcess must contain the getter and setter field reads");

        List<AbstractInsnNode> matchesAt13 = arraySetMatches(postProcess, accesses, 13);
        List<AbstractInsnNode> matchesAt14 = arraySetMatches(postProcess, accesses, 14);

        assertTrue(matchesAt13.isEmpty(), "fuzz=13 must not reach the createChest result store");
        assertEquals(1, matchesAt14.size(), "fuzz=14 must select exactly one hasPlacedChest store");
        assertEquals(Opcodes.BASTORE, matchesAt14.get(0).getOpcode());
    }

    @Test
    void officialTargetHasSeparateGetterAndSetterBytecode() throws Exception {
        MethodNode postProcess = postProcessMethod();
        List<FieldInsnNode> accesses = hasPlacedChestGets(postProcess);
        assertTrue(accesses.stream().anyMatch(field -> nextArrayOpcode(postProcess, field, Opcodes.BALOAD, 8)),
                "postProcess must read hasPlacedChest with BALOAD");
        assertTrue(accesses.stream().anyMatch(field -> nextArrayOpcode(postProcess, field, Opcodes.BASTORE, 14)),
                "postProcess must write hasPlacedChest with BASTORE within fuzz=14");
    }

    private static List<AbstractInsnNode> arraySetMatches(MethodNode method, List<FieldInsnNode> accesses, int fuzz) {
        List<AbstractInsnNode> matches = new ArrayList<>();
        for (FieldInsnNode access : accesses) {
            AbstractInsnNode match = BeforeFieldAccess.findArrayNode(method.instructions, access, Opcodes.BASTORE, fuzz);
            if (match != null) {
                matches.add(match);
            }
        }
        return matches;
    }

    private static boolean nextArrayOpcode(MethodNode method, FieldInsnNode access, int opcode, int fuzz) {
        return BeforeFieldAccess.findArrayNode(method.instructions, access, opcode, fuzz) != null;
    }

    private static List<FieldInsnNode> hasPlacedChestGets(MethodNode method) {
        List<FieldInsnNode> accesses = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && instruction.getOpcode() == Opcodes.GETFIELD
                    && OWNER.equals(field.owner)
                    && ("hasPlacedChest".equals(field.name) || "f_71081_".equals(field.name))
                    && "[Z".equals(field.desc)) {
                accesses.add(field);
            }
        }
        return accesses;
    }

    private static MethodNode postProcessMethod() throws Exception {
        try (InputStream input = DesertPyramidPiece.class.getClassLoader().getResourceAsStream(
                OWNER + ".class")) {
            assertNotNull(input, "official DesertPyramidPiece class bytes must be available");
            ClassNode owner = new ClassNode();
            new ClassReader(input).accept(owner, ClassReader.SKIP_DEBUG);
            return owner.methods.stream()
                    .filter(method -> "postProcess".equals(method.name) || "m_183269_".equals(method.name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing DesertPyramidPiece postProcess method"));
        }
    }
}
