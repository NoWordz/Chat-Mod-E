package com.niuqu.chatbubble.group;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupFileStoreMigrationTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID INVITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @TempDir
    Path temp;

    @Test
    void migratesCurrentMemberCohortToLegacyAccessWithoutInventingHistory() throws IOException {
        Path file = temp.resolve("e33chat-groups.json");
        String original = """
            {"groups":{"Guild":{"owner":"00000000-0000-0000-0000-000000000001",
              "members":["00000000-0000-0000-0000-000000000001",
                         "00000000-0000-0000-0000-000000000002"]}}}
            """;
        Files.writeString(file, original, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(file);
        GroupFileStore store = new GroupFileStore(file);

        GroupFileStore.Snapshot migrated = store.load();

        assertEquals(1, migrated.groups().size());
        GroupFileStore.GroupRecord record = migrated.groups().values().iterator().next();
        assertEquals("Guild", record.identity().displayName());
        assertTrue(record.access().isOwner(OWNER));
        assertTrue(record.access().isMember(MEMBER));
        assertEquals(Map.of(OWNER, 1L, MEMBER, 1L), record.access().visibleFromSequence());
        assertEquals(Set.of(OWNER, MEMBER), record.access().legacyHistoryReaders());
        assertTrue(migrated.invitations().isEmpty());

        String v2 = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(v2.contains("\"schemaVersion\": 2"));
        assertTrue(v2.contains(record.identity().id().toString()),
            "the migration assigns and persists one stable, non-name-based group UUID");
        assertFalse(v2.contains("\"lastSequence\""));
        assertFalse(v2.contains("\"messages\""));
        assertTrue(java.util.Arrays.equals(originalBytes,
            Files.readAllBytes(file.resolveSibling(file.getFileName() + ".v1.bak"))));

        GroupFileStore.Snapshot reopened = store.load();
        assertEquals(record.identity().id(), reopened.groups().keySet().iterator().next());
        assertEquals(Set.of(), reopened.invitations().keySet());
    }

    @Test
    void v2RoundTripPreservesStableIdentityAccessAndInvitation() throws IOException {
        Path file = temp.resolve("e33chat-groups.json");
        GroupIdentity identity = new GroupIdentity(GROUP_ID, "Guild");
        GroupAccess access = new GroupAccess(OWNER, Map.of(OWNER, 1L, MEMBER, 7L), Set.of(OWNER));
        GroupInvitation invitation = new GroupInvitation(
            INVITE_ID, GROUP_ID, OWNER, MEMBER, 100L, 200L);
        GroupFileStore.Snapshot snapshot = new GroupFileStore.Snapshot(
            Map.of(GROUP_ID, new GroupFileStore.GroupRecord(identity, access)),
            Map.of(INVITE_ID, invitation));

        new GroupFileStore(file).save(snapshot);
        GroupFileStore.Snapshot loaded = new GroupFileStore(file).load();

        assertEquals(identity, loaded.groups().get(GROUP_ID).identity());
        assertEquals(Map.of(OWNER, 1L, MEMBER, 7L),
            loaded.groups().get(GROUP_ID).access().visibleFromSequence());
        assertEquals(Set.of(OWNER), loaded.groups().get(GROUP_ID).access().legacyHistoryReaders());
        assertEquals(invitation, loaded.invitations().get(INVITE_ID));
    }

    @Test
    void invalidV1OwnerMembershipDoesNotReplaceTheOriginalFile() throws IOException {
        Path file = temp.resolve("e33chat-groups.json");
        String original = """
            {"groups":{"Guild":{"owner":"00000000-0000-0000-0000-000000000001",
              "members":["00000000-0000-0000-0000-000000000002"]}}}
            """;
        Files.writeString(file, original, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new GroupFileStore(file).load());

        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }
}
