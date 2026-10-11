package com.niuqu.chatbubble.server;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.niuqu.chatbubble.config.ChatServerConfig;
import com.niuqu.chatbubble.group.GroupAccess;
import com.niuqu.chatbubble.group.GroupFileStore;
import com.niuqu.chatbubble.group.GroupHistoryStore;
import com.niuqu.chatbubble.group.GroupIdentity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9 group-media authorization driven through the real production seams: the
 * refusal of a fetch is asserted call-by-call, not structurally. Headless — the
 * roster and the durable history store are installed through the same private
 * static fields the production loader fills, and a real {@link DiskMediaStore}
 * holds the bytes.
 *
 * <p>The model is the frozen UUID {@link MediaBinding}: a non-member of the
 * binding's group is refused, and a dissolved group's files are deleted.
 */
class GroupMediaAuthorizationTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000d7001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000d7002");
    private static final UUID LATE_MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000d7003");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-0000000d7004");
    private static final UUID GROUP_A = UUID.fromString("00000000-0000-0000-0000-0000000d7010");
    private static final UUID GROUP_B = UUID.fromString("00000000-0000-0000-0000-0000000d7011");

    @TempDir
    Path temp;

    private Field serverConfigChildConfig;
    private Object previousServerConfig;

    @BeforeEach
    void installGroupsGate() throws Exception {
        // groups_enabled is a cached ForgeConfigSpec value and no config file is loaded
        // headless, so the spec is handed an in-memory config first; the exact prior
        // config object is put back afterwards, so no later class sees a changed global.
        serverConfigChildConfig = ForgeConfigSpec.class.getDeclaredField("childConfig");
        serverConfigChildConfig.setAccessible(true);
        previousServerConfig = serverConfigChildConfig.get(ChatServerConfig.SERVER_CONFIG);
        ChatServerConfig.SERVER_CONFIG.setConfig(CommentedConfig.inMemory());
        ChatServerConfig.GROUPS_ENABLED.clearCache();
    }

    @AfterEach
    void restoreGroupsGate() throws Exception {
        serverConfigChildConfig.set(ChatServerConfig.SERVER_CONFIG, previousServerConfig);
    }

    @Test
    void nonMemberFetchOfGroupMediaIsRefused() throws Exception {
        GroupHistoryStore history = newGroupHistory("fetch");
        appendAll(history, GROUP_A, 5);
        DiskMediaStore store = new DiskMediaStore(temp.resolve("media"));
        String id = storeGroupUpload(store, 1, "alice", GROUP_A, MEMBER);

        withRoster(history, roster(), () -> {
            // A member can fetch while the file is still pending.
            assertTrue(MediaService.authorizeDownload(MEMBER, store, id),
                "the group member passes the media gate");
            assertTrue(MediaService.authorizeDownload(OWNER, store, id),
                "an owner who is also a member passes");
            // The head-line requirement: a non-member must be refused.
            assertFalse(MediaService.authorizeDownload(OUTSIDER, store, id),
                "a non-member must never read a group image");
        });
        history.close();
    }

    @Test
    void committedMediaIsReadableOnlyInsideTheJoinWindow() throws Exception {
        GroupHistoryStore history = newGroupHistory("window");
        appendAll(history, GROUP_A, 5);
        DiskMediaStore store = new DiskMediaStore(temp.resolve("media"));
        String id = storeGroupUpload(store, 1, "alice", GROUP_A, MEMBER);

        withRoster(history, roster(), () -> {
            store.commitBinding(id, 4); // row 4: inside MEMBER's window (2..5)
            assertTrue(MediaService.authorizeDownload(MEMBER, store, id),
                "the referring member reads the committed row's media");
            assertFalse(MediaService.authorizeDownload(LATE_MEMBER, store, id),
                "a member who joined after row 4 cannot read it");
            assertFalse(MediaService.authorizeDownload(OUTSIDER, store, id),
                "a non-member still cannot read it");
            assertEquals(4, store.binding(id).sequence());
        });
        history.close();
    }

    @Test
    void aPrivateFileWithNoBindingIsRefusedEvenForMembers() throws Exception {
        GroupHistoryStore history = newGroupHistory("unbound");
        DiskMediaStore store = new DiskMediaStore(temp.resolve("media"));
        String orphan = "g_0123456789abcdef0123456789abcdef";
        withRoster(history, roster(), () ->
            assertFalse(MediaService.authorizeDownload(MEMBER, store, orphan),
                "a g_ id with no binding is never public"));
        history.close();
    }

    @Test
    void aMemberCannotSmuggleAnotherGroupsMediaIntoAGroupMessage() throws Exception {
        GroupHistoryStore history = newGroupHistory("smuggle");
        DiskMediaStore store = new DiskMediaStore(temp.resolve("media"));
        String mineA = storeGroupUpload(store, 1, "alice", GROUP_A, MEMBER);
        String theirsB = storeGroupUpload(store, 2, "alice", GROUP_B, OWNER);
        String publicId = "0123456789abcdef0123456789abcdef";

        withRoster(history, roster(), () -> {
            assertTrue(MediaService.validateGroupMessage(MEMBER, GROUP_A,
                body(mineA), store), "a member may send their own group's media");
            assertFalse(MediaService.validateGroupMessage(MEMBER, GROUP_A,
                body(theirsB), store), "another group's private media is refused");
            assertTrue(MediaService.validateGroupMessage(MEMBER, GROUP_A,
                body(publicId), store), "a public id keeps its ordinary semantics");
            assertFalse(MediaService.validateGroupMessage(OUTSIDER, GROUP_A,
                "hello", store), "a non-member cannot say anything at all");
        });
        history.close();
    }

    @Test
    void dissolvingAGroupDeletesItsMediaAndKeepsOtherGroups() throws Exception {
        GroupHistoryStore history = newGroupHistory("disband");
        appendAll(history, GROUP_A, 3);
        DiskMediaStore store = new DiskMediaStore(temp.resolve("media"));
        String mineA = storeGroupUpload(store, 1, "alice", GROUP_A, MEMBER);
        String theirsB = storeGroupUpload(store, 2, "alice", GROUP_B, OWNER);

        withRoster(history, roster(), () -> {
            MediaService.onGroupDeleted(GROUP_A, store);
            assertEquals(-1, store.sizeOf(mineA), "the disbanded group's bytes are gone");
            assertEquals(null, store.binding(mineA), "and its binding is forgotten");
            assertNotNull(store.binding(theirsB), "another group's binding survives");
            assertEquals(10, store.sizeOf(theirsB));
            assertFalse(MediaService.authorizeDownload(MEMBER, store, mineA),
                "a deleted file is refused even for a member");
        });
        history.close();
    }

    // ==== fixtures ====

    /** Install a roster + durable store for the duration of {@code body}. */
    private void withRoster(GroupHistoryStore history, GroupFileStore.Snapshot roster, ThrowingRunnable body)
            throws Exception {
        Field stateField = GroupManager.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Field storeField = GroupManager.class.getDeclaredField("groupHistoryStore");
        storeField.setAccessible(true);
        GroupFileStore.Snapshot previousState = (GroupFileStore.Snapshot) stateField.get(null);
        Object previousStore = storeField.get(null);
        boolean previousEnabled = flipGroupsEnabled(true);
        try {
            stateField.set(null, roster);
            storeField.set(null, history);
            body.run();
        } finally {
            flipGroupsEnabled(previousEnabled);
            stateField.set(null, previousState);
            storeField.set(null, previousStore);
        }
    }

    /** OWNER and MEMBER joined at 1; MEMBER's window starts at 2 for the window test. */
    private static GroupFileStore.Snapshot roster() {
        LinkedHashMap<UUID, Long> aStarts = new LinkedHashMap<>();
        aStarts.put(OWNER, 1L);
        aStarts.put(MEMBER, 2L);
        aStarts.put(LATE_MEMBER, 5L);
        GroupFileStore.GroupRecord groupA = record(GROUP_A, "Guild-A", OWNER, aStarts, Set.of());
        LinkedHashMap<UUID, Long> bStarts = new LinkedHashMap<>();
        bStarts.put(OWNER, 1L);
        GroupFileStore.GroupRecord groupB = record(GROUP_B, "Guild-B", OWNER, bStarts, Set.of());
        LinkedHashMap<UUID, GroupFileStore.GroupRecord> groups = new LinkedHashMap<>();
        groups.put(GROUP_A, groupA);
        groups.put(GROUP_B, groupB);
        return new GroupFileStore.Snapshot(groups, Map.of());
    }

    private static GroupFileStore.GroupRecord record(UUID id, String name, UUID owner,
                                                     Map<UUID, Long> members, Set<UUID> legacy) {
        return new GroupFileStore.GroupRecord(new GroupIdentity(id, name),
            new GroupAccess(owner, new LinkedHashMap<>(members), new LinkedHashSet<>(legacy)));
    }

    private static String storeGroupUpload(DiskMediaStore store, long uploadId, String playerName,
                                           UUID groupId, UUID uploader) {
        store.noteGroupUpload(uploadId, playerName, groupId, uploader);
        assertTrue(store.beginUpload(uploadId, playerName, 1, 10, "image/png") == null,
            "begin must succeed");
        String id = store.acceptChunk(uploadId, 0, new byte[10]);
        assertNotNull(id);
        return id;
    }

    private static String body(String mediaId) {
        return "[[CICode,url=e33chat://media/" + mediaId + ",name=a.png]]";
    }

    private GroupHistoryStore newGroupHistory(String name) throws IOException {
        return new GroupHistoryStore(temp.resolve(name), 30, 1000, 1L << 20);
    }

    private static void appendAll(GroupHistoryStore store, UUID groupId, int count) throws IOException {
        for (int i = 1; i <= count; i++) {
            store.append(groupId, MEMBER, "Steve", "row-" + i, System.currentTimeMillis(), null, null);
        }
    }

    private static boolean flipGroupsEnabled(boolean value) {
        boolean previous = ChatServerConfig.GROUPS_ENABLED.get();
        ChatServerConfig.GROUPS_ENABLED.set(value);
        ChatServerConfig.GROUPS_ENABLED.clearCache();
        return previous;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
