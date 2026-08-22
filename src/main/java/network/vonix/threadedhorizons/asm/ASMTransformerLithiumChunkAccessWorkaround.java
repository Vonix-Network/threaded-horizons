package network.vonix.threadedhorizons.asm;

import org.objectweb.asm.tree.ClassNode;

public class ASMTransformerLithiumChunkAccessWorkaround {

    private ASMTransformerLithiumChunkAccessWorkaround() {
    }

    // Retired: the off-thread CFUtil.join onto mainThreadProcessor deadlocks /tp.
    // Do not re-enable from config; the mixin plugin also hard-skips this family.
    static void transform(ClassNode classNode) {
    }

}
