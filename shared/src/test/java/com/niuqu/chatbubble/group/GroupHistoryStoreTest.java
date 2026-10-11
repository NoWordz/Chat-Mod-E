package com.niuqu.chatbubble.group;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupHistoryStoreTest {
    private static final UUID GROUP_A = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID GROUP_B = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path temp;

    @Test
    void sequencesPersistPerGroupAndPagesAfterTheExclusiveCursor() throws IOException {
        Path root = temp.resolve("history");
        long now = System.currentTimeMillis();
        GroupHistoryStore first = new GroupHistoryStore(root, 30, 10, 4096L);
        assertEquals(1L, append(first, GROUP_A, "a-one", now).sequence());
        assertEquals(2L, append(first, GROUP_A, "a-two", now + 1).sequence());
        assertEquals(1L, append(first, GROUP_B, "b-one", now + 2).sequence());
        first.close();

        GroupHistoryStore reopened = new GroupHistoryStore(root, 30, 10, 4096L);

        assertEquals(2L, reopened.lastSequence(GROUP_A));
        assertEquals(1L, reopened.lastSequence(GROUP_B));
        assertEquals(List.of("a-two"), reopened.pageAfter(GROUP_A, 1L, 100)
            .stream().map(GroupHistoryEntry::content).toList());
        assertEquals(List.of("b-one"), reopened.pageAfter(GROUP_B, 0L, 100)
            .stream().map(GroupHistoryEntry::content).toList());
        reopened.close();
    }

    @Test
    void globalCountPruningKeepsSequenceHighWaterAndSurvivorSequence() throws IOException {
        GroupHistoryStore store = new GroupHistoryStore(temp.resolve("bounded"), 30, 2, 4096L);
        long now = System.currentTimeMillis();

        append(store, GROUP_A, "a-oldest", now - 2_000);
        append(store, GROUP_A, "a-newer", now - 1_000);
        append(store, GROUP_B, "b-newest", now);

        assertEquals(2L, store.lastSequence(GROUP_A));
        assertEquals(2L, store.retainedFromSequence(GROUP_A));
        assertEquals(List.of(2L), store.pageAfter(GROUP_A, 0L, 100)
            .stream().map(GroupHistoryEntry::sequence).toList());
        assertEquals(List.of(1L), store.pageAfter(GROUP_B, 0L, 100)
            .stream().map(GroupHistoryEntry::sequence).toList());
        store.close();
    }

    @Test
    void retentionPruningDoesNotRecycleAGroupSequence() throws IOException {
        GroupHistoryStore store = new GroupHistoryStore(temp.resolve("retention"), 30, 10, 4096L);
        long old = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000;

        append(store, GROUP_A, "expired", old);

        assertEquals(1L, store.lastSequence(GROUP_A));
        assertEquals(2L, store.retainedFromSequence(GROUP_A));
        assertEquals(List.of(), store.pageAfter(GROUP_A, 0L, 100));
        assertEquals(2L, append(store, GROUP_A, "new", System.currentTimeMillis()).sequence());
        store.close();
    }

    @Test
    void byteCapPrunesOversizedStoredRowWithoutReusingItsSequence() throws IOException {
        Path root = temp.resolve("bytes");
        GroupHistoryStore store = new GroupHistoryStore(root, 30, 10, 128L);

        append(store, GROUP_A, "x".repeat(512), System.currentTimeMillis());

        assertEquals(1L, store.lastSequence(GROUP_A));
        assertEquals(2L, store.retainedFromSequence(GROUP_A));
        assertEquals(List.of(), store.pageAfter(GROUP_A, 0L, 100));
        store.close();
        assertEquals(1L, new GroupHistoryStore(root, 30, 10, 128L).lastSequence(GROUP_A));
    }

    @Test
    void groupDeletionRemovesRowsAndSequenceIndex() throws IOException {
        Path root = temp.resolve("deleted");
        GroupHistoryStore store = new GroupHistoryStore(root, 30, 10, 4096L);
        append(store, GROUP_A, "private", System.currentTimeMillis());

        store.delete(GROUP_A);

        assertEquals(0L, store.lastSequence(GROUP_A));
        assertEquals(List.of(), store.pageAfter(GROUP_A, 0L, 100));
        store.close();
        GroupHistoryStore reopened = new GroupHistoryStore(root, 30, 10, 4096L);
        assertEquals(0L, reopened.lastSequence(GROUP_A));
        reopened.close();
    }

    private static GroupHistoryEntry append(GroupHistoryStore store, UUID groupId,
                                            String content, long time) throws IOException {
        return store.append(groupId, SENDER, "Steve", content, time, null, null);
    }
}
