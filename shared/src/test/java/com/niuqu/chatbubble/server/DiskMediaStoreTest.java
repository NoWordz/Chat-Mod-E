package com.niuqu.chatbubble.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Headless tests for the server media store (pure Java, no MC classes). */
class DiskMediaStoreTest {

    private static byte[] payload(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * 31);
        return b;
    }

    @Test
    void uploadFlowRoundTrips() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir);
        byte[] data = payload(100_000);
        int chunks = DiskMediaStore.totalChunksFor(data.length);
        long uploadId = 1;
        assertNull(store.beginUpload(uploadId, "alice", chunks, data.length, "image/png"));
        String mediaId = null;
        for (int i = 0; i < chunks; i++) {
            int from = i * DiskMediaStore.CHUNK_BYTES;
            int len = Math.min(DiskMediaStore.CHUNK_BYTES, data.length - from);
            String r = store.acceptChunk(uploadId, i, Arrays.copyOfRange(data, from, from + len));
            if (i == chunks - 1) mediaId = r;
            else assertNull(r, "mid-upload must return null");
        }
        assertNotNull(mediaId);
        assertTrue(DiskMediaStore.isValidMediaId(mediaId));
        assertEquals(data.length, store.sizeOf(mediaId));

        // read back in chunks and reassemble
        int total = DiskMediaStore.totalChunksFor(data.length);
        byte[] out = new byte[data.length];
        int off = 0;
        for (int i = 0; i < total; i++) {
            byte[] c = store.readChunk(mediaId, i, total);
            assertNotNull(c);
            System.arraycopy(c, 0, out, off, c.length);
            off += c.length;
        }
        assertArrayEquals(data, out);
    }

    @Test
    void rejectsOversizedFile() {
        DiskMediaStore store = new DiskMediaStore(Path.of("nonexistent"),
            DiskMediaStore.MAX_SINGLE_BYTES, DiskMediaStore.QUOTA_BYTES, 0);
        String r = store.beginUpload(1, "alice", 100, DiskMediaStore.MAX_SINGLE_BYTES + 1, "image/png");
        assertEquals("too large", r);
    }

    @Test
    void rejectsQuotaExceeded() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir, 1024 * 1024, 10_000, 0);
        String r = store.beginUpload(1, "alice", 1, 20_000, "image/png");
        assertEquals("quota exceeded", r);
        // within quota passes
        assertNull(store.beginUpload(2, "alice", 1, 1_000, "image/png"));
    }

    @Test
    void rejectsOutOfOrderChunk() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir);
        assertNull(store.beginUpload(1, "alice", 2, 100, "image/png"));
        String r = store.acceptChunk(1, 1, new byte[50]); // skip index 0
        assertEquals("chunk out of order", r);
        assertEquals(-1, store.sizeOf("whatever"));
        // session discarded: a valid 0-chunk no longer accepted
        assertNull(store.acceptChunk(1, 0, new byte[50]));
    }

    @Test
    void rejectsSizeMismatch() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir);
        assertNull(store.beginUpload(1, "alice", 2, 100, "image/png"));
        String r = store.acceptChunk(1, 0, new byte[50]);
        assertNull(r);
        r = store.acceptChunk(1, 1, new byte[60]); // 110 != 100
        assertNotNull(r);
        assertNotEquals("", r);
        assertEquals("size mismatch", r);
    }

    // ---- M1 defect 2: per-player byte quota + per-player file-count cap ----

    /** Upload one complete file on behalf of {@code player}. */
    private static String storeFile(DiskMediaStore store, long uploadId, String player, int size) {
        int chunks = DiskMediaStore.totalChunksFor(size);
        String begin = store.beginUpload(uploadId, player, chunks, size, "image/png");
        assertNull(begin, "begin must be accepted: " + begin);
        String id = null;
        for (int i = 0; i < chunks; i++) {
            int len = Math.min(DiskMediaStore.CHUNK_BYTES, size - i * DiskMediaStore.CHUNK_BYTES);
            id = store.acceptChunk(uploadId, i, new byte[len]);
        }
        assertNotNull(id);
        return id;
    }

    @Test
    void capsPerPlayerBytesAcrossFiles() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        // Global quota is generous; the per-player quota is what must bind.
        DiskMediaStore store = new DiskMediaStore(dir, DiskMediaStore.MAX_SINGLE_BYTES,
            1_000_000, 0, 10_000, 100);
        storeFile(store, 1, "alice", 4_000);
        storeFile(store, 2, "alice", 4_000); // alice now holds 8_000 of 10_000
        // A third 4 kB file would exceed the per-player cap.
        assertEquals("quota exceeded", store.beginUpload(3, "alice", 1, 4_000, "image/png"));
        // Bob is unaffected by alice being full.
        assertNull(store.beginUpload(4, "bob", 1, 8_000, "image/png"));
    }

    @Test
    void rejectedPerPlayerUploadConsumesNoQuota() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir, DiskMediaStore.MAX_SINGLE_BYTES,
            1_000_000, 0, 10_000, 100);
        storeFile(store, 1, "alice", 8_000);
        assertEquals("quota exceeded", store.beginUpload(2, "alice", 1, 4_000, "image/png"));
        // The rejected attempt must not have reserved anything: a 2 kB file that
        // fits the remaining 2_000 bytes is still accepted.
        assertNull(store.beginUpload(3, "alice", 1, 2_000, "image/png"));
    }

    @Test
    void capsPerPlayerFileCount() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir, DiskMediaStore.MAX_SINGLE_BYTES,
            DiskMediaStore.QUOTA_BYTES, 0, DiskMediaStore.MAX_PLAYER_BYTES, 3);
        storeFile(store, 1, "alice", 10);
        storeFile(store, 2, "alice", 10);
        storeFile(store, 3, "alice", 10);
        assertEquals("too many files", store.beginUpload(4, "alice", 1, 10, "image/png"));
        assertNull(store.beginUpload(5, "bob", 1, 10, "image/png"));
    }

    @Test
    void zeroByteUploadIsRejected() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir);
        assertEquals("too large", store.beginUpload(1, "alice", 1, 0, "image/png"));
    }

    // ---- M1 defect 3: acceptChunk must not walk the directory (M11) ----

    /**
     * The real M11 probe. The store is pointed at a directory whose filesystem
     * counts every directory enumeration, so an old {@code dirSize()} —
     * {@code Files.walk(dir)} returning inside {@code acceptChunk} — is observed
     * and fails this test. Asserting a production counter would not do that:
     * such a counter only moves where the production code chooses to bump it, so
     * a re-introduced raw walk would leave it untouched (the earlier version of
     * this test passed with the walk put back).
     */
    @Test
    void acceptChunkDoesNotScanTheDirectory() throws IOException {
        Path real = Files.createTempDirectory("e33media");
        // Seed files so an O(files) walk would be obvious.
        for (int i = 0; i < 50; i++) {
            Files.write(real.resolve(DiskMediaStore.newMediaId()), new byte[16]);
        }
        CountingFileSystem.Wrapped counting = CountingFileSystem.wrap(real);
        DiskMediaStore store = new DiskMediaStore(counting.path());
        assertNull(store.beginUpload(1, "alice", 3, 1_500, "image/png"));
        int before = counting.directoryStreamCount();
        assertNull(store.acceptChunk(1, 0, new byte[600]));
        assertNull(store.acceptChunk(1, 1, new byte[600]));
        assertNotNull(store.acceptChunk(1, 2, new byte[300])); // final chunk returns the mediaId
        assertEquals(before, counting.directoryStreamCount(),
            "acceptChunk must answer the quota from incremental counters, not enumerate the directory");

        // Sanity: the probe is live — an explicit walk over the same wrapped
        // directory does move the count, so a blank result above cannot be a
        // broken probe.
        int probeBefore = counting.directoryStreamCount();
        try (var s = Files.walk(counting.path())) { s.forEach(p -> {}); }
        assertTrue(counting.directoryStreamCount() > probeBefore,
            "the counting filesystem must observe a real Files.walk");
    }

    @Test
    void chunkBeyondAdvertisedTotalIsRefusedBeforeWriting() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir);
        assertNull(store.beginUpload(1, "alice", 3, 1_000, "image/png"));
        assertNull(store.acceptChunk(1, 0, new byte[600]));
        // 600 + 600 > the advertised 1_000: refused and the session ended here,
        // instead of being written and only noticed on the final chunk.
        assertEquals("size mismatch", store.acceptChunk(1, 1, new byte[600]));
        assertNull(store.acceptChunk(1, 2, new byte[600]));
    }

    @Test
    void readMissingReturnsNull() {
        DiskMediaStore store = new DiskMediaStore(Path.of("nonexistent"));
        assertNull(store.readChunk("00000000000000000000000000000000", 0, 1));
        assertEquals(-1, store.sizeOf("00000000000000000000000000000000"));
    }

    @Test
    void mediaIdValidation() {
        assertTrue(DiskMediaStore.isValidMediaId("0123456789abcdef0123456789abcdef"));
        assertFalse(DiskMediaStore.isValidMediaId(null));
        assertFalse(DiskMediaStore.isValidMediaId(""));
        assertFalse(DiskMediaStore.isValidMediaId("0123456789abcdef0123456789abcde"));
        assertFalse(DiskMediaStore.isValidMediaId("0123456789abcdef0123456789ABCDEF"));
        assertFalse(DiskMediaStore.isValidMediaId("../../etc/passwd"));
        assertFalse(DiskMediaStore.isValidMediaId("0123456789abcdef-123456789abcdef"));
    }

    @Test
    void cleanupExpiredOnlyRemovesOld() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir, DiskMediaStore.MAX_SINGLE_BYTES,
            DiskMediaStore.QUOTA_BYTES, 1_000);
        long id = 1;
        assertNull(store.beginUpload(id, "alice", 1, 10, "image/png"));
        String mediaId = store.acceptChunk(id, 0, new byte[10]);
        assertNotNull(mediaId);
        long old = System.currentTimeMillis() - 5_000;
        Files.setLastModifiedTime(dir.resolve(mediaId), FileTime.fromMillis(old));
        // fresh upload stays
        assertNull(store.beginUpload(id + 1, "alice", 1, 10, "image/png"));
        String fresh = store.acceptChunk(id + 1, 0, new byte[10]);
        assertNotNull(fresh);

        int removed = store.cleanupExpired();
        assertEquals(1, removed);
        assertEquals(-1, store.sizeOf(mediaId));
        assertEquals(10, store.sizeOf(fresh));
    }

    @Test
    void urlValidationMatchesStore() {
        // isUsableUrl-style check for the e33chat protocol (logic lives in ImageLoader;
        // here we only pin the mediaId contract used by both sides)
        assertTrue(DiskMediaStore.isValidMediaId("abcdef0123456789abcdef0123456789"));
        assertTrue(DiskMediaStore.isValidMediaId(DiskMediaStore.newMediaId()));
    }

    @Test
    void throttledCleanupRunsOncePerInterval() throws IOException {
        Path dir = Files.createTempDirectory("e33media");
        DiskMediaStore store = new DiskMediaStore(dir, DiskMediaStore.MAX_SINGLE_BYTES,
            DiskMediaStore.QUOTA_BYTES, 1_000);
        assertNull(store.beginUpload(1, "alice", 1, 10, "image/png"));
        String mediaId = store.acceptChunk(1, 0, new byte[10]);
        assertNotNull(mediaId);
        Files.setLastModifiedTime(dir.resolve(mediaId),
            FileTime.fromMillis(System.currentTimeMillis() - 5_000));
        store.cleanupExpiredThrottled();
        assertEquals(-1, store.sizeOf(mediaId));
        // Second call inside the interval is a no-op even with a new expired file
        assertNull(store.beginUpload(2, "alice", 1, 10, "image/png"));
        String second = store.acceptChunk(2, 0, new byte[10]);
        assertNotNull(second);
        Files.setLastModifiedTime(dir.resolve(second),
            FileTime.fromMillis(System.currentTimeMillis() - 5_000));
        store.cleanupExpiredThrottled();
        assertEquals(10, store.sizeOf(second));
    }

    // ---- wire chunk-count bound: the client uses this to size its
    // reassembly array from a count that arrives over the network ----

    @Test void chunkCountBoundMatchesThePerFileLimit() {
        assertEquals(DiskMediaStore.totalChunksFor(DiskMediaStore.MAX_SINGLE_BYTES),
            DiskMediaStore.maxTotalChunks());
        assertTrue(DiskMediaStore.isValidChunkCount(1));
        assertTrue(DiskMediaStore.isValidChunkCount(DiskMediaStore.maxTotalChunks()));
    }

    @Test void implausibleChunkCountsAreRejected() {
        assertFalse(DiskMediaStore.isValidChunkCount(0));
        assertFalse(DiskMediaStore.isValidChunkCount(-1));
        assertFalse(DiskMediaStore.isValidChunkCount(DiskMediaStore.maxTotalChunks() + 1));
        assertFalse(DiskMediaStore.isValidChunkCount(Integer.MAX_VALUE));
    }
}
