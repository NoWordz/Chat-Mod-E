package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.config.ChatServerConfig;
import com.niuqu.chatbubble.group.GroupAccess;
import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupFileStore;
import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import com.niuqu.chatbubble.group.GroupHistoryStore;
import com.niuqu.chatbubble.group.GroupIdentity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-path regressions for the v2 directory, page and media authorization: they
 * drive the manager's real durable read math against real {@link GroupHistoryStore} files
 * and real {@link GroupFileStore.Snapshot} rosters (no live server, no source-text
 * assertions). The snapshot is installed through the same private static field the
 * production loader fills, so the permission seams read exactly what the server would.
 */
class GroupHistoryAuthorizationTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000b002");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-00000000b003");
    private static final UUID GROUP_A = UUID.fromString("00000000-0000-0000-0000-00000000b010");
    private static final UUID GROUP_B = UUID.fromString("00000000-0000-0000-0000-00000000b011");
    private static final UUID SENDER = MEMBER;

    @TempDir
    Path temp;

    @Test
    void pageStartsAtTheClosedJoinLowerBoundAndAdvancesTheCursor() throws IOException {
        GroupHistoryStore store = newStore("join-bound", 1000);
        appendAll(store, GROUP_A, 5);
        LinkedHashMap<UUID, Long> roster = visible(OWNER);
        roster.put(MEMBER, 3L);
        GroupAccess access = new GroupAccess(OWNER, roster, Set.of());

        GroupHistoryPage page = GroupManager.pageFor(access, MEMBER, GROUP_A, 0L, 100, store, true);

        assertEquals(List.of(3L, 4L, 5L), sequences(page));
        assertEquals(5L, page.nextSequence());
        assertFalse(page.hasMore());
        store.close();
    }

    @Test
    void pageClampsCursorAndLimitAndNeverOverflows() throws IOException {
        GroupHistoryStore store = newStore("clamp", 1000);
        appendAll(store, GROUP_A, 5);
        GroupAccess access = new GroupAccess(OWNER, visible(OWNER, MEMBER), Set.of());

        GroupHistoryPage negativeCursor = GroupManager.pageFor(access, MEMBER, GROUP_A, -9L, 2, store, true);
        assertEquals(List.of(1L, 2L), sequences(negativeCursor));
        assertEquals(2L, negativeCursor.nextSequence());
        assertTrue(negativeCursor.hasMore());

        assertTrue(GroupManager.pageFor(access, MEMBER, GROUP_A, 0L, 0, store, true).entries().isEmpty(),
            "zero limit returns an empty page");
        assertTrue(GroupManager.pageFor(access, MEMBER, GROUP_A, 0L, -4, store, true).entries().isEmpty(),
            "negative limit returns an empty page");

        GroupHistoryPage exhausted = GroupManager.pageFor(access, MEMBER, GROUP_A, Long.MAX_VALUE, 100, store, true);
        assertTrue(exhausted.entries().isEmpty());
        assertEquals(Long.MAX_VALUE, exhausted.nextSequence(), "a max cursor must not overflow");
        assertFalse(exhausted.hasMore());
        store.close();
    }

    @Test
    void pageLimitIsCappedAtOneHundredRowsAndChainsWithTheReturnedCursor() throws IOException {
        GroupHistoryStore store = newStore("cap", 1000);
        appendAll(store, GROUP_A, 105);
        GroupAccess access = new GroupAccess(OWNER, visible(OWNER, MEMBER), Set.of());

        GroupHistoryPage first = GroupManager.pageFor(access, MEMBER, GROUP_A, 0L, 1000, store, true);
        assertEquals(100, first.entries().size());
        assertEquals(100L, first.nextSequence());
        assertTrue(first.hasMore());

        GroupHistoryPage second = GroupManager.pageFor(access, MEMBER, GROUP_A, first.nextSequence(), 1000, store, true);
        assertEquals(List.of(101L, 102L, 103L, 104L, 105L), sequences(second));
        assertFalse(second.hasMore());
        store.close();
    }

    @Test
    void departedThenRejoinedMemberOnlySeesRowsFromTheNewJoinBound() throws IOException {
        GroupHistoryStore store = newStore("rejoin", 1000);
        appendAll(store, GROUP_A, 8);

        // Left: no roster entry at all -> nothing, cursor echoed.
        GroupAccess afterLeaving = new GroupAccess(OWNER, visible(OWNER), Set.of());
        assertEquals(0, GroupManager.pageFor(afterLeaving, MEMBER, GROUP_A, 0L, 100, store, true).entries().size());

        // Rejoined at 6: rows 1-5 are unreachable even though the store still holds them.
        LinkedHashMap<UUID, Long> roster = visible(OWNER);
        roster.put(MEMBER, 6L);
        GroupAccess afterRejoining = new GroupAccess(OWNER, roster, Set.of());

        GroupHistoryPage page = GroupManager.pageFor(afterRejoining, MEMBER, GROUP_A, 0L, 100, store, true);

        assertEquals(List.of(6L, 7L, 8L), sequences(page));
        assertFalse(page.hasMore());
        store.close();
    }

    @Test
    void nonMemberAndDepartedMemberGetTheSameShapedEmptyPage() throws IOException {
        GroupHistoryStore store = newStore("empty-shape", 1000);
        appendAll(store, GROUP_A, 7);
        GroupAccess ownerOnly = new GroupAccess(OWNER, visible(OWNER), Set.of());

        GroupHistoryPage outsider = GroupManager.pageFor(ownerOnly, OUTSIDER, GROUP_A, 0L, 100, store, true);
        assertTrue(outsider.entries().isEmpty());
        assertFalse(outsider.hasMore());
        assertEquals(0L, outsider.nextSequence(), "an outsider sees the echoed cursor, never the latest sequence");

        GroupHistoryPage departed = GroupManager.pageFor(ownerOnly, MEMBER, GROUP_A, 7L, 100, store, true);
        assertTrue(departed.entries().isEmpty());
        assertFalse(departed.hasMore());
        assertEquals(7L, departed.nextSequence());
        store.close();
    }

    @Test
    void pageNeverServesBelowTheRetainedWindow() throws IOException {
        GroupHistoryStore store = newStore("pruned", 2);
        appendAll(store, GROUP_A, 5);
        assertEquals(4L, store.retainedFromSequence(GROUP_A), "fixture must have pruned rows 1-3");
        GroupAccess access = new GroupAccess(OWNER, visible(OWNER, MEMBER), Set.of());

        GroupHistoryPage page = GroupManager.pageFor(access, MEMBER, GROUP_A, 0L, 100, store, true);

        assertEquals(List.of(4L, 5L), sequences(page));
        assertFalse(page.hasMore());
        store.close();
    }

    @Test
    void directoryListsOnlyCurrentMembershipsWithDurableCounters() throws IOException {
        GroupHistoryStore store = newStore("directory", 100);
        append(store, GROUP_A, "a-one");
        append(store, GROUP_A, "a-two");
        append(store, GROUP_B, "b-one");

        GroupFileStore.GroupRecord memberGroup =
            record(GROUP_A, "Guild-A", OWNER, visible(OWNER, MEMBER), Set.of(MEMBER));
        GroupFileStore.GroupRecord foreignGroup =
            record(GROUP_B, "Guild-B", OWNER, visible(OWNER), Set.of());

        List<GroupDirectoryEntry> directory =
            GroupManager.directoryFor(List.of(memberGroup, foreignGroup), MEMBER, store, true);

        assertEquals(1, directory.size(), "only current memberships are listed");
        GroupDirectoryEntry entry = directory.get(0);
        assertEquals(GROUP_A, entry.identity().id());
        assertEquals("Guild-A", entry.identity().displayName());
        assertEquals(OWNER, entry.ownerId());
        assertEquals(Set.of(OWNER, MEMBER), new HashSet<>(entry.memberIds()));
        assertEquals(1L, entry.visibleFromSequence());
        assertTrue(entry.legacyHistoryAllowed());
        assertEquals(2L, entry.latestSequence());
        assertEquals(1L, entry.retainedFromSequence());

        assertTrue(GroupManager.directoryFor(List.of(memberGroup, foreignGroup), OUTSIDER, store, true).isEmpty(),
            "a non-member is projected no row at all, not even for a group they can see by name");
        GroupDirectoryEntry ownerRow = GroupManager.directoryFor(
                List.of(memberGroup, foreignGroup), OWNER, store, true).stream()
            .filter(row -> row.identity().id().equals(GROUP_A)).findFirst().orElseThrow();
        assertFalse(ownerRow.legacyHistoryAllowed(),
            "the legacy-reader flag belongs only to the migrated cohort, never to every member");
        store.close();
    }

    @Test
    void directoryKeepsTwoGroupsWithTheSameDisplayNameIsolated() throws IOException {
        GroupHistoryStore store = newStore("same-name", 100);
        append(store, GROUP_A, "old-one");
        append(store, GROUP_A, "old-two");
        append(store, GROUP_B, "new-one");

        // The durable snapshot refuses two groups sharing a display name (names are
        // attributes, identity is the group UUID), so this drives the pure projection
        // with the row pair directly: whichever route a name takes, the rows and the
        // counters must come from the recipient's own group UUID.
        GroupFileStore.GroupRecord oldGroup = record(GROUP_A, "Guild", OWNER, visible(OWNER), Set.of());
        GroupFileStore.GroupRecord newGroup = record(GROUP_B, "Guild", OWNER, visible(OWNER, MEMBER), Set.of());

        List<GroupDirectoryEntry> directory =
            GroupManager.directoryFor(List.of(oldGroup, newGroup), MEMBER, store, true);

        assertEquals(1, directory.size());
        assertEquals(GROUP_B, directory.get(0).identity().id(),
            "a display name shared with a retired group must not hand over its history");
        assertEquals(1L, directory.get(0).latestSequence(), "sequence counters stay per group UUID");
        store.close();
    }

    @Test
    void disabledGroupsServeNoDirectory() throws IOException {
        GroupHistoryStore store = newStore("disabled-directory", 100);
        append(store, GROUP_A, "a-one");
        GroupFileStore.GroupRecord memberGroup =
            record(GROUP_A, "Guild-A", OWNER, visible(OWNER, MEMBER), Set.of(MEMBER));

        assertTrue(GroupManager.directoryFor(List.of(memberGroup), MEMBER, store, false).isEmpty(),
            "groups_enabled=false must not hand out a recipient's memberships");
        assertEquals(1, GroupManager.directoryFor(List.of(memberGroup), MEMBER, store, true).size(),
            "the same fixture still yields its directory while groups are on");
        store.close();
    }

    @Test
    void disabledGroupsServeAnEmptyPageThatLeaksNoSequence() throws IOException {
        GroupHistoryStore store = newStore("disabled-page", 100);
        appendAll(store, GROUP_A, 5);
        GroupAccess access = new GroupAccess(OWNER, visible(OWNER, MEMBER), Set.of());

        GroupHistoryPage disabled = GroupManager.pageFor(access, MEMBER, GROUP_A, 2L, 100, store, false);

        assertTrue(disabled.entries().isEmpty());
        assertFalse(disabled.hasMore());
        assertEquals(2L, disabled.nextSequence(),
            "the disabled page echoes only the caller's own cursor, never the durable latest sequence");
        assertEquals(List.of(3L, 4L, 5L),
            sequences(GroupManager.pageFor(access, MEMBER, GROUP_A, 2L, 100, store, true)));
        store.close();
    }

    @Test
    void appendFailureIsReportedAsNullInsteadOfFanningOut() throws IOException {
        Path root = temp.resolve("broken");
        Files.createDirectories(root);
        // A plain file where the per-group directory must be created makes the row write fail.
        Files.write(root.resolve(GROUP_A.toString()), new byte[]{1});
        GroupHistoryStore store = new GroupHistoryStore(root, 30, 10, 1L << 20);

        assertNull(GroupManager.appendGroupHistory(GROUP_A, SENDER, "Steve", "hi", 1000L, null, null, store));
        assertEquals(0L, store.lastSequence(GROUP_A), "a failed append must not advance the sequence");
        assertNull(GroupManager.appendGroupHistory(GROUP_A, SENDER, "Steve", "hi", 1000L, null, null, null),
            "a missing durable store is a reported failure, not an exception");
        store.close();
    }

    @Test
    void mediaSeamsReadOnlyTheDurableRosterAndGrantOwnersNoExtraWindow() throws Exception {
        GroupHistoryStore store = newStore("media-seams", 1000);
        appendAll(store, GROUP_A, 5);

        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>();
        starts.put(OWNER, 4L);    // the owner only joined at row 4
        starts.put(MEMBER, 2L);
        GroupFileStore.GroupRecord group = record(GROUP_A, "Guild-A", OWNER, starts, Set.of(MEMBER));
        GroupFileStore.Snapshot roster = new GroupFileStore.Snapshot(Map.of(GROUP_A, group), Map.of());

        Field stateField = GroupManager.class.getDeclaredField("state");
        stateField.setAccessible(true);
        GroupFileStore.Snapshot previousState = (GroupFileStore.Snapshot) stateField.get(null);
        Field storeField = GroupManager.class.getDeclaredField("groupHistoryStore");
        storeField.setAccessible(true);
        Object previousStore = storeField.get(null);
        Boolean previousEnabled = flipGroupsEnabled(Boolean.TRUE);
        try {
            stateField.set(null, roster);
            storeField.set(null, store);

            assertTrue(GroupManager.isMemberById(MEMBER, GROUP_A), "a current member passes the media gate");
            assertTrue(GroupManager.isMemberById(OWNER, GROUP_A), "the owning player is a member too");
            assertFalse(GroupManager.isMemberById(OUTSIDER, GROUP_A), "a non-member fails the media gate");
            assertFalse(GroupManager.isMemberById(MEMBER, GROUP_B), "an unknown group hands out no membership");
            assertFalse(GroupManager.isMemberById(null, GROUP_A));

            assertTrue(GroupManager.canReadGroupSequence(MEMBER, GROUP_A, 2L),
                "the member's own join sequence is readable");
            assertTrue(GroupManager.canReadGroupSequence(MEMBER, GROUP_A, 5L),
                "every row up to the highest allocated sequence is readable");
            assertFalse(GroupManager.canReadGroupSequence(MEMBER, GROUP_A, 1L),
                "a row below the member's join sequence stays closed");
            assertFalse(GroupManager.canReadGroupSequence(MEMBER, GROUP_A, 6L),
                "no sequence above the highest allocated row is readable");
            assertFalse(GroupManager.canReadGroupSequence(MEMBER, GROUP_A, 0L),
                "sequence 0 is never an allocated row");
            assertFalse(GroupManager.canReadGroupSequence(OUTSIDER, GROUP_A, 2L),
                "a non-member gets no window even inside the sequence range");
            assertFalse(GroupManager.canReadGroupSequence(MEMBER, GROUP_B, 2L),
                "an unknown group gets no window");
            assertFalse(GroupManager.canReadGroupSequence(MEMBER, null, 2L),
                "a missing group id gets no window");

            // Row 3 exists and is inside MEMBER's window, yet the owner — whose own join
            // is row 4 — cannot read it: ownership (and OP) grants no extra window.
            assertTrue(GroupManager.canReadGroupSequence(MEMBER, GROUP_A, 3L));
            assertFalse(GroupManager.canReadGroupSequence(OWNER, GROUP_A, 3L),
                "the owner reads only from the owner's own join sequence onward");

            // The same installed roster through the production page entry point: the
            // member reads their own window, an unknown group answers with the
            // non-member-shaped empty page that echoes only the caller's cursor.
            assertEquals(List.of(2L, 3L, 4L, 5L),
                sequences(GroupManager.buildHistoryPage(MEMBER, GROUP_A, 1L, 100)),
                "the production page entry point reads the member window off the installed snapshot");
            GroupHistoryPage unknown = GroupManager.buildHistoryPage(MEMBER, GROUP_B, 7L, 100);
            assertTrue(unknown.entries().isEmpty());
            assertFalse(unknown.hasMore());
            assertEquals(7L, unknown.nextSequence(),
                "an unknown group must not leak the durable latest sequence");

            flipGroupsEnabled(Boolean.FALSE);
            assertFalse(GroupManager.isMemberById(MEMBER, GROUP_A),
                "groups_enabled=false closes the media membership gate");
            assertFalse(GroupManager.canReadGroupSequence(MEMBER, GROUP_A, 2L),
                "groups_enabled=false closes group media reads");
        } finally {
            flipGroupsEnabled(previousEnabled);
            stateField.set(null, previousState);
            storeField.set(null, previousStore);
            store.close();
        }
    }

    /**
     * The gate is a cached {@code ModConfigSpec} value and no config file is loaded in a
     * headless test, so the cache is flipped directly and the exact prior value (null =
     * never loaded) restored, so no later class sees a changed global.
     */
    private static Boolean flipGroupsEnabled(Boolean value) throws Exception {
        Field cached = ModConfigSpec.ConfigValue.class.getDeclaredField("cachedValue");
        cached.setAccessible(true);
        Boolean previous = (Boolean) cached.get(ChatServerConfig.GROUPS_ENABLED);
        cached.set(ChatServerConfig.GROUPS_ENABLED, value);
        return previous;
    }

    private GroupHistoryStore newStore(String name, int maxMessages) throws IOException {
        return new GroupHistoryStore(temp.resolve(name), 30, maxMessages, 1L << 20);
    }

    private static void appendAll(GroupHistoryStore store, UUID groupId, int count) throws IOException {
        for (int i = 1; i <= count; i++) append(store, groupId, "row-" + i);
    }

    private static void append(GroupHistoryStore store, UUID groupId, String content) throws IOException {
        // Wall-clock time: a synthetic epoch-ms value would look expired to the retention window.
        store.append(groupId, SENDER, "Steve", content, System.currentTimeMillis(), null, null);
    }

    private static LinkedHashMap<UUID, Long> visible(UUID... members) {
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>();
        for (UUID member : members) starts.put(member, 1L);
        return starts;
    }

    /** One durable roster row, in exactly the shape {@link GroupFileStore} persists and reads. */
    private static GroupFileStore.GroupRecord record(UUID id, String name, UUID owner,
                                                     Map<UUID, Long> members, Set<UUID> legacy) {
        return new GroupFileStore.GroupRecord(new GroupIdentity(id, name),
            new GroupAccess(owner, new LinkedHashMap<>(members), new LinkedHashSet<>(legacy)));
    }

    private static List<Long> sequences(GroupHistoryPage page) {
        return page.entries().stream().map(GroupHistoryEntry::sequence).toList();
    }
}
