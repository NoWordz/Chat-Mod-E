package com.niuqu.chatbubble.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group ownership + privacy gate for server-hosted media (2.5.0 M9): an upload
 * scope keyed by the real connection UUID + uploadId decides whether the bytes
 * become a group-private file, and a refused scope must never fall through to a
 * public save. The durable binding is keyed by the group <em>UUID</em>, the one
 * frozen authorization model (§13 D6). Headless (pure Java, no MC classes).
 */
class GroupMediaBindingTest {

    private static final UUID GROUP = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_GROUP = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final UUID UPLOADER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static String storeGroupUpload(DiskMediaStore store, long uploadId, String playerName,
                                          UUID groupId, int size) {
        store.noteGroupUpload(uploadId, playerName, groupId, UPLOADER);
        assertNull(store.beginUpload(uploadId, playerName, 1, size, "image/png"));
        String id = store.acceptChunk(uploadId, 0, new byte[size]);
        assertNotNull(id);
        return id;
    }

    @Test
    void scopedGroupUploadRecordsAPendingBinding() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir);
        String id = storeGroupUpload(store, 7, "alice", GROUP, 10);

        // Group files use the private namespace, never the public 32-hex shape.
        assertTrue(DiskMediaStore.isPrivateMediaId(id));
        assertFalse(DiskMediaStore.isPublicMediaId(id));
        MediaBinding binding = store.binding(id);
        assertNotNull(binding);
        assertEquals(GROUP, binding.groupId());
        assertEquals(UPLOADER, binding.uploaderId());
        assertTrue(binding.pending());
        assertEquals(10, store.usedBytes());
    }

    @Test
    void aSecondFirstChunkCannotReplaceTheGroupSession() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir);
        store.noteGroupUpload(7, "alice", GROUP, UPLOADER);
        // The declared plan must equal totalChunksFor(bytes): with CHUNK_BYTES at
        // 512 KiB, a two-chunk session is one full chunk plus a short tail.
        byte[] full = new byte[DiskMediaStore.CHUNK_BYTES];
        int tail = 10;
        long totalBytes = (long) full.length + tail;
        assertNull(store.beginUpload(7, "alice", 2, totalBytes, "image/png"));

        // A later first chunk for the same uploadId must not silently turn the
        // in-progress group session into a public one.
        assertEquals("in progress", store.beginUpload(7, "alice", 1, tail, "image/png"));
        assertNull(store.acceptChunk(7, 0, full), "mid-upload returns null");
        String id = store.acceptChunk(7, 1, new byte[tail]);
        assertNotNull(id);
        assertTrue(DiskMediaStore.isPrivateMediaId(id));
        assertNotNull(store.binding(id));
    }

    @Test
    void bindingSurvivesAStoreRestart() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        String id = storeGroupUpload(new DiskMediaStore(dir), 7, "alice", GROUP, 10);

        DiskMediaStore reloaded = new DiskMediaStore(dir);
        MediaBinding binding = reloaded.binding(id);
        assertNotNull(binding);
        assertTrue(binding.pending());
        assertEquals(GROUP, binding.groupId());
    }

    @Test
    void refusedScopeNeverStoresPublicBytes() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir);
        store.refuseUpload(11);

        // The context was refused (group gone / not a member): the following
        // chunks must not create a session, so nothing is ever stored publicly.
        assertEquals("group denied", store.beginUpload(11, "alice", 1, 10, "image/png"));
        assertNull(store.acceptChunk(11, 0, new byte[10]));
        assertEquals(0, store.usedBytes());
        assertNull(store.binding("00000000000000000000000000000000"));

        // The denial is not consumed by that one attempt: a repeated first chunk
        // (from either upload path) is still refused.
        assertTrue(store.isUploadDenied(11));
        assertEquals("group denied", store.beginUpload(11, "alice", 1, 10, "image/png"));
        assertEquals(0, store.usedBytes());
    }

    @Test
    void scopeBelongsToTheRegisteringConnection() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir);
        store.noteGroupUpload(3, "alice", GROUP, UPLOADER);

        assertEquals("group denied", store.beginUpload(3, "bob", 1, 10, "image/png"));
        assertEquals(0, store.usedBytes());
    }

    @Test
    void bindingPersistFailureDeletesTheFileInsteadOfServingIt() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        // Force persistBindings() to fail: the bindings path is a directory.
        Files.createDirectory(dir.resolveSibling(dir.getFileName() + ".bindings.json"));
        DiskMediaStore store = new DiskMediaStore(dir);
        store.noteGroupUpload(5, "alice", GROUP, UPLOADER);
        assertNull(store.beginUpload(5, "alice", 1, 10, "image/png"));

        assertEquals("io", store.acceptChunk(5, 0, new byte[10]));
        assertEquals(0, store.usedBytes());
        // The bytes must not remain on disk unbound.
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void committingBindsTheFileToTheMessageSequenceDurably() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir);
        String id = storeGroupUpload(store, 7, "alice", GROUP, 10);

        store.commitBinding(id, 42);
        assertFalse(store.binding(id).pending());
        assertEquals(42, store.binding(id).sequence());

        assertEquals(42, new DiskMediaStore(dir).binding(id).sequence());
    }

    @Test
    void committingAnUnknownOrAlreadyCommittedBindingIsANoOp() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir);
        String id = storeGroupUpload(store, 1, "alice", GROUP, 10);
        store.commitBinding(id, 5);
        // Committing again must not re-open or move a committed binding.
        store.commitBinding(id, 99);
        assertEquals(5, store.binding(id).sequence());

        assertDoesNotThrow(() -> store.commitBinding("00000000000000000000000000000000", 1));
    }

    @Test
    void dissolvingAGroupDeletesOnlyThatGroupsFiles() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir);
        String mine = storeGroupUpload(store, 1, "alice", GROUP, 10);
        String theirs = storeGroupUpload(store, 2, "alice", OTHER_GROUP, 20);

        assertEquals(1, store.deleteGroupMedia(GROUP));
        assertEquals(-1, store.sizeOf(mine));
        assertNull(store.binding(mine));
        assertEquals(20, store.sizeOf(theirs));
        assertNotNull(store.binding(theirs));
        assertEquals(20, store.usedBytes());
    }

    @Test
    void uploadedMediaIdsAreExtractedFromMessageBodies() {
        String id = "0123456789abcdef0123456789abcdef";
        String priv = "g_0123456789abcdef0123456789abcdef";
        String body = "look [[CICode,url=e33chat://media/" + id + ",name=a.png]] and "
            + "[[CICode,url=e33chat://media/" + priv + ",name=b.gif]] and "
            + "https://example.invalid/c.png";
        assertEquals(List.of(id, priv), DiskMediaStore.hostedMediaIds(body));
        assertEquals(List.of(), DiskMediaStore.hostedMediaIds(null));
        assertEquals(List.of(), DiskMediaStore.hostedMediaIds("no media here"));
    }

    @Test
    void disconnectingReleasesTheReservation() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir, 1024, 1000, 0);
        assertNull(store.beginUpload(9, "alice", 1, 900, "image/png"));
        assertEquals(900, store.reservedBytes());

        store.discardUploadsFor("alice");
        assertEquals(0, store.reservedBytes());
        // The freed reservation is reusable.
        assertNull(store.beginUpload(10, "alice", 1, 900, "image/png"));
    }

    @Test
    void publicUploadsStayPublicAndUnbound() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        DiskMediaStore store = new DiskMediaStore(dir);

        assertNull(store.beginUpload(1, "alice", 1, 10, "image/png"));
        String id = store.acceptChunk(1, 0, new byte[10]);

        assertNotNull(id);
        assertTrue(DiskMediaStore.isPublicMediaId(id), "an unscoped upload stays public");
        assertNull(store.binding(id), "a public upload carries no group binding");
    }

    @Test
    void anExistingPrivateFileWithoutItsBindingIsNeverServed() throws IOException {
        Path dir = Files.createTempDirectory("e33mediagroup");
        Files.createDirectories(dir);
        // A stray g_ file with no bindings document: the private shape is refused
        // by the download caller (binding == null is not "public" for g_ ids).
        Files.write(dir.resolve("g_0123456789abcdef0123456789abcdef"), new byte[4]);
        DiskMediaStore store = new DiskMediaStore(dir);
        assertNull(store.binding("g_0123456789abcdef0123456789abcdef"));
        assertTrue(DiskMediaStore.isStoredMediaId("g_0123456789abcdef0123456789abcdef"));
        assertTrue(DiskMediaStore.isPrivateMediaId("g_0123456789abcdef0123456789abcdef"));
        assertFalse(DiskMediaStore.isPublicMediaId("g_0123456789abcdef0123456789abcdef"));
    }
}
