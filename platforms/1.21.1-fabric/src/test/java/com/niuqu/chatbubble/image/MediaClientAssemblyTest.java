package com.niuqu.chatbubble.image;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 defect 4 / audit M12+L9: chunk packets from the remote server are
 * untrusted. Assemblies must only exist for a media id the client requested,
 * and an out-of-range index must not register a zombie assembly that pins
 * {@code MAX_ACTIVE_ASSEMBLIES} and permanently refuses later fetches.
 */
class MediaClientAssemblyTest {

    private static String id(int n) {
        return String.format("%032x", n);
    }

    @AfterEach
    void reset() {
        MediaClient.clearStateForTest();
    }

    @Test
    void chunksForAnUnrequestedIdCreateNoAssembly() {
        for (int i = 0; i < 16; i++) {
            assertEquals(MediaClient.ChunkOutcome.UNREQUESTED,
                MediaClient.handleChunk(id(i), 0, new byte[4], 1),
                "an id we never requested must be ignored");
        }
        assertEquals(0, MediaClient.activeAssemblyCountForTest(),
            "no assembly may be created for an unrequested id");
        assertEquals(0, MediaClient.pendingFetchCountForTest());
    }

    @Test
    void aRequestedUploadAssemblesAndCompletes() {
        String mediaId = id(1);
        CompletableFuture<byte[]> pending = MediaClient.registerPendingFetchForTest(mediaId);
        assertEquals(MediaClient.ChunkOutcome.ASSEMBLING,
            MediaClient.handleChunk(mediaId, 0, new byte[]{1, 2}, 2));
        assertEquals(MediaClient.ChunkOutcome.COMPLETE,
            MediaClient.handleChunk(mediaId, 1, new byte[]{3, 4, 5}, 2));
        assertTrue(pending.isDone() && !pending.isCompletedExceptionally());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, pending.getNow(null));
        assertEquals(0, MediaClient.activeAssemblyCountForTest(),
            "the assembly is released once it completes");
        assertEquals(0, MediaClient.pendingFetchCountForTest());
    }

    @Test
    void anOutOfRangeIndexDoesNotLeakAZombieAssembly() {
        String mediaId = id(2);
        CompletableFuture<byte[]> pending = MediaClient.registerPendingFetchForTest(mediaId);
        // index 1 with totalChunks 1: reaches the buffer only after registration in
        // the old code, leaving an entry FETCH_TOUCH never saw.
        assertEquals(MediaClient.ChunkOutcome.REJECTED,
            MediaClient.handleChunk(mediaId, 1, new byte[4], 1));
        assertEquals(0, MediaClient.activeAssemblyCountForTest(),
            "an impossible index must not register an assembly");
        // The real chunk still arrives and completes normally.
        assertEquals(MediaClient.ChunkOutcome.COMPLETE,
            MediaClient.handleChunk(mediaId, 0, new byte[]{9}, 1));
        assertArrayEquals(new byte[]{9}, pending.getNow(null));
    }

    @Test
    void zombieChunksCannotPermanentlyBlockLaterFetches() {
        // 16 hostile chunks for ids the client never requested (the exact M12
        // attack). They must not consume any of the 16 assembly slots.
        for (int i = 0; i < 16; i++) {
            MediaClient.handleChunk(id(100 + i), 1, new byte[4], 1);
        }
        assertEquals(0, MediaClient.activeAssemblyCountForTest());
        // A legitimate fetch afterwards still works.
        String mediaId = id(3);
        CompletableFuture<byte[]> pending = MediaClient.registerPendingFetchForTest(mediaId);
        assertEquals(MediaClient.ChunkOutcome.COMPLETE,
            MediaClient.handleChunk(mediaId, 0, new byte[]{7, 7}, 1));
        assertArrayEquals(new byte[]{7, 7}, pending.getNow(null));
    }

    @Test
    void aFailedRequestCannotBeCompletedLater() {
        String mediaId = id(4);
        CompletableFuture<byte[]> pending = MediaClient.registerPendingFetchForTest(mediaId);
        // A not-found sentinel fails the request and removes it...
        assertEquals(MediaClient.ChunkOutcome.REJECTED,
            MediaClient.handleChunk(mediaId, 0, new byte[0], 1));
        assertTrue(pending.isCompletedExceptionally());
        // ...so a late chunk for the same, no-longer-in-flight id is ignored.
        assertEquals(MediaClient.ChunkOutcome.UNREQUESTED,
            MediaClient.handleChunk(mediaId, 0, new byte[]{1}, 1));
    }
}
