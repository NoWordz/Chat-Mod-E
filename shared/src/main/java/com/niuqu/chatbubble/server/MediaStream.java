package com.niuqu.chatbubble.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * One open read handle over a stored media file, returning sequential chunks (2.5.0 M9).
 *
 * <p>Exists so a download never re-opens and re-stats the file once per chunk
 * (the old {@code DiskMediaStore.readChunk} did both for every 512 KiB). The
 * stream is owned by exactly one reader and is not thread-safe; a transfer
 * closes it when it finishes, is cancelled, the player disconnects, or the
 * server stops.
 */
public final class MediaStream implements Closeable {
    private final String mediaId;
    private final InputStream in;
    private final long size;
    private final int totalChunks;
    private int nextIndex;

    MediaStream(String mediaId, InputStream in, long size) {
        this.mediaId = mediaId;
        this.in = in;
        this.size = size;
        this.totalChunks = DiskMediaStore.totalChunksFor(size);
    }

    public String mediaId() { return mediaId; }

    public long size() { return size; }

    public int totalChunks() { return totalChunks; }

    /** Next chunk in order, or {@code null} at the end or on a short/IO read. */
    public byte[] nextChunk() {
        if (nextIndex >= totalChunks) return null;
        long start = (long) nextIndex * DiskMediaStore.CHUNK_BYTES;
        int len = (int) Math.min(DiskMediaStore.CHUNK_BYTES, size - start);
        try {
            byte[] out = in.readNBytes(len);
            if (out.length != len) return null;
            nextIndex++;
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // The handle is being dropped either way; a failed close has nothing
            // left to release and no caller that could act on it.
        }
    }
}
