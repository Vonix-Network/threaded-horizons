package network.vonix.threadedhorizons.compatibility;

import net.minecraft.core.BlockPos;
import network.vonix.threadedhorizons.compatibility.common.IsleOfBerkWorldAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IsleOfBerkWorldAccessTest {

    @Test
    void missingLevelFailsClosed() {
        BlockPos pos = new BlockPos(16, 64, 16);
        assertFalse(IsleOfBerkWorldAccess.hasChunkAt(null, pos));
        assertFalse(IsleOfBerkWorldAccess.hasChunkAt(null, null));
        assertThrows(IllegalStateException.class, () -> IsleOfBerkWorldAccess.requireChunk(null, pos, "probe"));
        assertThrows(IllegalStateException.class, () -> IsleOfBerkWorldAccess.canSeeSky(null, pos));
        assertThrows(IllegalStateException.class, () -> IsleOfBerkWorldAccess.biome(null, pos));
        assertThrows(IllegalStateException.class, () -> IsleOfBerkWorldAccess.biome(null, null));
    }
}
