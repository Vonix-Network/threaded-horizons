package network.vonix.threadedhorizons.common.notickvd;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNoTickSortBudgetTest {

    @Test
    void pendingTicketSortIsSkippedWhenEveryEntryFitsTheBudget() throws Exception {
        String body = Files.readString(Path.of(
                "src/main/java/network/vonix/threadedhorizons/common/notickvd/PlayerNoTickDistanceMap.java"));
        assertTrue(body.contains("pendingTicketUpdates.size() > MAX_TICKET_UPDATES_PER_TICK"), body);
        assertFalse(body.contains("sourceChunks.toLongArray()"), body);
        assertTrue(body.contains("iterator.nextLong()"), body);
    }
}
