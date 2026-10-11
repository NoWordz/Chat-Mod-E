package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.group.GroupAccess;
import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupFileStore;
import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import com.niuqu.chatbubble.group.GroupHistoryStore;
import com.niuqu.chatbubble.group.GroupIdentity;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the production page selector — and the directory/permission projections
 *  that read the same snapshot — against the actual bounded durable store and the real
 *  static roster. */
class GroupHistoryPageBoundsTest {
    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-0000000000b4");
    @TempDir Path temp;
    private Field stateField;
    private GroupFileStore.Snapshot previousState;
    private Field storeField;
    private Object previousStore;
    private GroupHistoryStore store;

    @BeforeEach
    void installDurableFixture() throws Exception {
        stateField = GroupManager.class.getDeclaredField("state");
        stateField.setAccessible(true);
        previousState = (GroupFileStore.Snapshot) stateField.get(null);
        storeField = GroupManager.class.getDeclaredField("groupHistoryStore");
        storeField.setAccessible(true);
        previousStore = storeField.get(null);
        store = new GroupHistoryStore(temp, 7, 200, 1L << 20);
        setMembership(1L);
        storeField.set(null, store);
    }

    @AfterEach
    void restoreStaticState() throws Exception {
        try {
            if (store != null) store.close();
        } finally {
            if (stateField != null) stateField.set(null, previousState);
            if (storeField != null) storeField.set(null, previousStore);
        }
    }

    @Test
    void fullPageContinuesThroughTheRealBoundedStore() throws Exception {
        appendRows(store, 105);
        GroupHistoryPage first = GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, 100);
        assertEquals(100, first.entries().size());
        assertEquals(100L, first.nextSequence());
        assertTrue(first.hasMore(), "rows 101-105 must trigger another page");
        GroupHistoryPage second = GroupManager.buildHistoryPage(MEMBER, GROUP, first.nextSequence(), 100);
        assertEquals(List.of(101L, 102L, 103L, 104L, 105L), sequences(second));
        assertFalse(second.hasMore());
    }

    @Test
    void exactFullPageHasNoPhantomNextPage() throws Exception {
        appendRows(store, 100);
        GroupHistoryPage page = GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, 100);
        assertEquals(100L, page.nextSequence());
        assertEquals(100, page.entries().size());
        assertFalse(page.hasMore());
    }

    @Test
    void smallerPagesChainWithAnExclusiveCursor() throws Exception {
        appendRows(store, 3);
        GroupHistoryPage first = GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, 2);
        assertEquals(List.of(1L, 2L), sequences(first));
        assertTrue(first.hasMore());
        GroupHistoryPage second = GroupManager.buildHistoryPage(MEMBER, GROUP, first.nextSequence(), 2);
        assertEquals(List.of(3L), sequences(second));
        assertFalse(second.hasMore());
    }

    @Test
    void negativeCursorStartsAtOneButNonpositiveLimitsReturnNoRows() throws Exception {
        appendRows(store, 3);
        assertEquals(List.of(1L, 2L), sequences(GroupManager.buildHistoryPage(MEMBER, GROUP, -5L, 2)));
        assertEquals(List.of(), sequences(GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, 0)));
        assertEquals(List.of(), sequences(GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, -7)));
    }

    @Test
    void longMaxCursorAndOutsidersRevealNoDurableRows() throws Exception {
        appendRows(store, 3);
        GroupHistoryPage exhausted = GroupManager.buildHistoryPage(MEMBER, GROUP, Long.MAX_VALUE, 100);
        assertEquals(List.of(), sequences(exhausted));
        assertEquals(Long.MAX_VALUE, exhausted.nextSequence());
        assertFalse(exhausted.hasMore());
        GroupHistoryPage denied = GroupManager.buildHistoryPage(OUTSIDER, GROUP, 0L, 100);
        assertEquals(List.of(), sequences(denied));
        assertEquals(0L, denied.nextSequence());
        assertFalse(denied.hasMore());
    }

    @Test
    void departedThenRejoinedMemberStartsAtTheNewClosedBoundary() throws Exception {
        appendRows(store, 5);
        setMembership(null);
        assertEquals(List.of(), sequences(GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, 100)));
        setMembership(4L);
        assertEquals(List.of(4L, 5L), sequences(GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, 100)));
    }

    @Test
    void retentionRaisesTheMemberWindowWithoutResettingSequences() throws Exception {
        try (GroupHistoryStore retained = new GroupHistoryStore(temp.resolve("retained"), 7, 2, 1L << 20)) {
            appendRows(retained, 5);
            storeField.set(null, retained);
            GroupHistoryPage page = GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, 100);
            assertEquals(List.of(4L, 5L), sequences(page));
            assertFalse(page.hasMore());
        } finally {
            storeField.set(null, store);
        }
    }

    @Test
    void disabledGroupsYieldNoDirectoryAndNoPageButRecoverWhenReEnabled() throws Exception {
        appendRows(store, 3);
        assertEquals(1, GroupManager.buildDirectory(MEMBER).size());

        boolean previous = flipGroupsEnabled(false);
        try {
            assertTrue(GroupManager.buildDirectory(MEMBER).isEmpty(),
                "groups_enabled=false must not hand out the recipient's memberships");
            GroupHistoryPage page = GroupManager.buildHistoryPage(MEMBER, GROUP, 2L, 100);
            assertTrue(page.entries().isEmpty());
            assertFalse(page.hasMore());
            assertEquals(2L, page.nextSequence(),
                "the disabled page echoes only the caller's own cursor, never the durable latest sequence");
        } finally {
            flipGroupsEnabled(previous);
        }

        assertEquals(List.of(1L, 2L, 3L), sequences(GroupManager.buildHistoryPage(MEMBER, GROUP, 0L, 100)));
    }

    @Test
    void directoryProjectsMemberLegacyAndNonMemberDifferently() throws Exception {
        appendRows(store, 3);

        List<GroupDirectoryEntry> member = GroupManager.buildDirectory(MEMBER);
        assertEquals(1, member.size());
        GroupDirectoryEntry entry = member.get(0);
        assertEquals(GROUP, entry.identity().id());
        assertEquals("Guild", entry.identity().displayName());
        assertEquals(OWNER, entry.ownerId());
        assertEquals(List.of(OWNER, MEMBER), entry.memberIds());
        assertEquals(1L, entry.visibleFromSequence());
        assertTrue(entry.legacyHistoryAllowed(), "the migrated member keeps the legacy cohort flag");
        assertEquals(3L, entry.latestSequence(), "the directory reports the durable latest sequence");
        assertEquals(1L, entry.retainedFromSequence());
        assertFalse(GroupManager.buildDirectory(OWNER).get(0).legacyHistoryAllowed(),
            "the owner was never in the migrated cohort");
        assertTrue(GroupManager.buildDirectory(OUTSIDER).isEmpty(),
            "a non-member receives no group row at all");
    }

    @Test
    void canReadGroupSequenceStaysInsideTheMembersWindowAndTheDurableLatest() throws Exception {
        appendRows(store, 5);
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>();
        starts.put(OWNER, 4L);
        starts.put(MEMBER, 4L);
        installGroup(starts, Set.of());

        assertTrue(GroupManager.isMemberById(MEMBER, GROUP));
        assertFalse(GroupManager.isMemberById(OUTSIDER, GROUP));
        assertTrue(GroupManager.canReadGroupSequence(MEMBER, GROUP, 4L));
        assertTrue(GroupManager.canReadGroupSequence(MEMBER, GROUP, 5L));
        assertFalse(GroupManager.canReadGroupSequence(MEMBER, GROUP, 3L),
            "a row before the join sequence is outside the member window");
        assertFalse(GroupManager.canReadGroupSequence(MEMBER, GROUP, 6L),
            "no sequence above the durable latest is readable");
        assertFalse(GroupManager.canReadGroupSequence(OWNER, GROUP, 3L),
            "ownership grants no window beyond the owner's own join sequence");
        assertFalse(GroupManager.canReadGroupSequence(OUTSIDER, GROUP, 4L));
        assertFalse(GroupManager.canReadGroupSequence(MEMBER, null, 4L));

        boolean previous = flipGroupsEnabled(false);
        try {
            assertFalse(GroupManager.canReadGroupSequence(MEMBER, GROUP, 4L),
                "group media authorization must close while groups_enabled=false");
            assertFalse(GroupManager.isMemberById(MEMBER, GROUP));
        } finally {
            flipGroupsEnabled(previous);
        }
    }

    /** The flag is a private static in the mod initializer; flip it without the GUI
     *  and always restore it, so no later class sees a changed global. */
    private static boolean flipGroupsEnabled(boolean value) throws Exception {
        Field field = com.niuqu.chatbubble.ChatBubbleMod.class.getDeclaredField("groupsEnabled");
        field.setAccessible(true);
        boolean previous = field.getBoolean(null);
        field.setBoolean(null, value);
        return previous;
    }

    private void setMembership(Long start) throws Exception {
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>();
        starts.put(OWNER, 1L);
        Set<UUID> legacy = new LinkedHashSet<>();
        if (start != null) {
            starts.put(MEMBER, start);
            legacy.add(MEMBER);   // MEMBER is part of the migrated v1 cohort
        }
        installGroup(starts, legacy);
    }

    /** Replaces the real static roster with one group holding exactly these members. */
    private void installGroup(Map<UUID, Long> starts, Set<UUID> legacy) throws Exception {
        GroupFileStore.GroupRecord record = new GroupFileStore.GroupRecord(
            new GroupIdentity(GROUP, "Guild"), new GroupAccess(OWNER, starts, legacy));
        stateField.set(null, new GroupFileStore.Snapshot(Map.of(GROUP, record), Map.of()));
    }

    private static void appendRows(GroupHistoryStore store, int count) throws Exception {
        for (int i = 1; i <= count; i++) {
            store.append(GROUP, OWNER, "Steve", "m" + i, System.currentTimeMillis(), null, null);
        }
    }

    private static List<Long> sequences(GroupHistoryPage page) {
        return page.entries().stream().map(GroupHistoryEntry::sequence).toList();
    }
}
