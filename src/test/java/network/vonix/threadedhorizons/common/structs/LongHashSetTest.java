package network.vonix.threadedhorizons.common.structs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongHashSetTest {

    @Test
    void unboxedAddContainsAndRemove() {
        LongHashSet set = new LongHashSet();
        assertTrue(set.add(ChunkKey));
        assertFalse(set.add(ChunkKey));
        assertTrue(set.contains(ChunkKey));
        assertEquals(1, set.size());
        assertTrue(set.remove(ChunkKey));
        assertTrue(set.isEmpty());
    }

    @Test
    void implementationDoesNotBoxIntoHashSetLong() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/structs/LongHashSet.java"));
        assertTrue(body.contains("LongOpenHashSet"));
        assertFalse(body.contains("new HashSet<Long>"));
        assertFalse(body.contains("HashSet<Long> delegate"));
    }

    private static final long ChunkKey = 42L;
}
