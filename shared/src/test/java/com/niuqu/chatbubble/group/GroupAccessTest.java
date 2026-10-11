package com.niuqu.chatbubble.group;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupAccessTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void sequenceBoundaryAndLegacyGrantAreRevokedOnRejoin() {
        GroupAccess current = new GroupAccess(OWNER,
            Map.of(OWNER, 1L, MEMBER, 21L), Set.of(OWNER, MEMBER));

        assertTrue(current.canRead(MEMBER, 21L));
        assertFalse(current.canRead(MEMBER, 20L));
        assertFalse(current.canRead(STRANGER, 21L));
        assertTrue(current.canReadLegacy(MEMBER));
        assertFalse(current.canLeave(OWNER));

        GroupAccess rejoined = new GroupAccess(OWNER,
            Map.of(OWNER, 1L, MEMBER, 41L), Set.of(OWNER));
        assertFalse(rejoined.canRead(MEMBER, 40L));
        assertTrue(rejoined.canRead(MEMBER, 41L));
        assertFalse(rejoined.canReadLegacy(MEMBER));
    }

    @Test
    void accessSnapshotIsImmutableAndRejectsInvalidMembership() {
        Map<UUID, Long> starts = new HashMap<>();
        starts.put(OWNER, 1L);
        Set<UUID> legacy = new HashSet<>(Set.of(OWNER));
        GroupAccess access = new GroupAccess(OWNER, starts, legacy);

        starts.put(MEMBER, 1L);
        legacy.add(MEMBER);
        assertFalse(access.isMember(MEMBER));
        assertFalse(access.canReadLegacy(MEMBER));
        assertThrows(UnsupportedOperationException.class,
            () -> access.visibleFromSequence().put(MEMBER, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new GroupAccess(OWNER, Map.of(MEMBER, 1L), Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new GroupAccess(OWNER, Map.of(OWNER, 1L), Set.of(MEMBER)));
    }

    @Test
    void groupRenameKeepsStableIdentity() {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        GroupIdentity original = new GroupIdentity(groupId, "Guild");

        GroupIdentity renamed = original.renamed("Guild2");

        assertEquals(groupId, renamed.id());
        assertEquals("Guild2", renamed.displayName());
    }
}
