package com.niuqu.chatbubble.image;

import com.mojang.logging.LogUtils;
import com.niuqu.chatbubble.network.NetworkHandler;
import com.niuqu.chatbubble.packets.GroupMediaContextPayload;
import com.niuqu.chatbubble.packets.GroupMediaUploadPayload;
import com.niuqu.chatbubble.packets.MediaRequestPayload;
import com.niuqu.chatbubble.packets.MediaResponsePayload;
import com.niuqu.chatbubble.packets.MediaUploadAckPayload;
import com.niuqu.chatbubble.packets.MediaUploadPayload;
import com.niuqu.chatbubble.server.DiskMediaStore;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Client side of the server media hosting feature (2.3.13).
 *
 * Capability: the server advertises media hosting in MediaCapPayload
 * (mediaEnabled). When enabled, chat image uploads go to the server
 * (e33chat://media/<id>, permanent) instead of the third-party host; when
 * disabled or absent, callers fall back to the existing ImageUploader path.
 *
 * Both upload and fetch are blocking with a 30s timeout and must be called on
 * a worker thread (not the render thread). Replies are matched by uploadId /
 * mediaId futures.
 *
 * <p>Chunk packets are untrusted input: the sender is the remote server. An
 * assembly is only created for a media id the client actually requested (kept
 * in {@link #FETCHES}), so a hostile server can no longer pin {@code
 * MAX_ACTIVE_ASSEMBLIES} zombie assemblies and permanently refuse every later
 * fetch (2.5.0 media audit M12/L9). Assemblies past their idle window are swept
 * by a janitor, and the per-id byte budget caps what one id may accumulate.
 */
public final class MediaClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TIMEOUT_SECONDS = 30;

    private static volatile boolean serverEnabled;
    private static final Map<Long, CompletableFuture<String>> UPLOADS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<byte[]>> FETCHES = new ConcurrentHashMap<>();
    private static final Map<String, byte[][]> FETCH_BUFFERS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> FETCH_COUNTS = new ConcurrentHashMap<>();

    // Reassembly bounds for a hostile/compromised server: chunk packets can name
    // any valid media id, so cap how many assemblies may coexist, how many bytes
    // one id may accumulate, and how long a silent assembly may linger.
    private static final int MAX_ACTIVE_ASSEMBLIES = 16;
    private static final long MAX_ASSEMBLY_BYTES = DiskMediaStore.MAX_SINGLE_BYTES;
    private static final long ASSEMBLY_IDLE_MILLIS = 60_000;
    private static final Map<String, Long> FETCH_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, Long> FETCH_TOUCH = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService JANITOR =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "e33chat-media-janitor");
            t.setDaemon(true);
            return t;
        });

    static {
        JANITOR.scheduleWithFixedDelay(MediaClient::dropIdleAssemblies,
            ASSEMBLY_IDLE_MILLIS, ASSEMBLY_IDLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** What feeding one wire chunk into the reassembly state did. */
    enum ChunkOutcome { UNREQUESTED, ASSEMBLING, COMPLETE, REJECTED }

    /**
     * What we uploaded, kept locally so we never have to download it back.
     *
     * <p>Every download costs one of the server's four-per-ten-seconds slots, and
     * re-reading our own upload is pure waste: it cannot be missing, and a small
     * LRU makes repeat views (scrolling back, relogging, reopening history) free.
     * Before this, pasting a few images in a row exhausted the quota with the
     * sender's <em>own</em> pictures — the first thing anyone tries.
     */
    private static final int OWN_UPLOAD_CACHE_ENTRIES = 24;
    private static final Map<String, byte[]> OWN_UPLOADS = new ConcurrentHashMap<>();
    private static final java.util.Deque<String> OWN_UPLOAD_ORDER = new java.util.ArrayDeque<>();

    private static void rememberOwnUpload(String mediaId, byte[] bytes) {
        if (mediaId == null || bytes == null || bytes.length == 0) return;
        synchronized (OWN_UPLOAD_ORDER) {
            OWN_UPLOADS.put(mediaId, bytes);
            OWN_UPLOAD_ORDER.remove(mediaId);
            OWN_UPLOAD_ORDER.addLast(mediaId);
            while (OWN_UPLOAD_ORDER.size() > OWN_UPLOAD_CACHE_ENTRIES) {
                OWN_UPLOADS.remove(OWN_UPLOAD_ORDER.removeFirst());
            }
        }
    }

    private MediaClient() {}

    public static void setServerEnabled(boolean b) { serverEnabled = b; }
    public static boolean serverEnabled() { return serverEnabled; }

    /** Client-side receiver (registered from NetworkHandler, dist-guarded). */
    public static void handleAck(MediaUploadAckPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            CompletableFuture<String> f = UPLOADS.remove(payload.uploadId());
            if (f != null) {
                f.complete(payload.error() == null ? payload.mediaId() : null);
            }
        });
    }

    /** Shared failure path: drop per-id assembly state and surface the error. */
    private static void failFetch(String id, String message) {
        FETCH_BUFFERS.remove(id);
        FETCH_COUNTS.remove(id);
        FETCH_BYTES.remove(id);
        FETCH_TOUCH.remove(id);
        CompletableFuture<byte[]> f = FETCHES.remove(id);
        if (f != null) f.completeExceptionally(new RuntimeException(message));
    }

    /** Janitor: drop half-assembled media whose chunks stopped arriving. */
    private static void dropIdleAssemblies() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : FETCH_TOUCH.entrySet()) {
            if (now - e.getValue() <= ASSEMBLY_IDLE_MILLIS) continue;
            String id = e.getKey();
            if (!FETCH_TOUCH.remove(id, e.getValue())) continue;
            LOGGER.info("[e33chat] server media assembly {} dropped after {}s idle",
                id, ASSEMBLY_IDLE_MILLIS / 1000);
            failFetch(id, "media assembly idle timeout: " + id);
        }
    }

    /** Client-side receiver (registered from NetworkHandler, dist-guarded). */
    public static void handleResponse(MediaResponsePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> handleChunk(payload.mediaId(), payload.index(),
            payload.chunk(), payload.totalChunks()));
    }

    /**
     * Reassembly core, separated from the packet plumbing so it is unit-testable.
     *
     * <p>The first check is the {@link #FETCHES} gate: an id the client never
     * requested is ignored outright, so no assembly entry, slot or byte budget is
     * ever created for it (audit M12/L9). Only a live request may allocate.
     */
    static ChunkOutcome handleChunk(String id, int index, byte[] chunk, int totalChunks) {
        if (id == null || chunk == null) return ChunkOutcome.REJECTED;
        if (totalChunks == 1 && chunk.length == 0) {
            // Not-found sentinel
            failFetch(id, "media not found: " + id);
            return ChunkOutcome.REJECTED;
        }
        // Gate: never assemble something we did not ask for. A hostile server
        // cannot fill MAX_ACTIVE_ASSEMBLIES with zombie entries this way.
        if (FETCHES.get(id) == null) {
            return ChunkOutcome.UNREQUESTED;
        }
        // Allocation bounds: totalChunks, the chunk stream and the reassembled
        // size all come off the wire, so an unclamped array lets one hostile
        // response OOM the client. The legitimate max is whatever the server
        // allows per upload (8 MB in 512 KB chunks).
        if (!DiskMediaStore.isValidChunkCount(totalChunks)) {
            failFetch(id, "media chunk count out of range: " + id);
            return ChunkOutcome.REJECTED;
        }
        // The index is validated against the advertised count *before* any
        // assembly is registered: an out-of-range index used to create a
        // FETCH_BUFFERS entry that no ordered packet ever touched, leaking a slot
        // held forever against MAX_ACTIVE_ASSEMBLIES (audit L9).
        if (index < 0 || index >= totalChunks) return ChunkOutcome.REJECTED;
        dropIdleAssemblies();
        byte[][] buf = FETCH_BUFFERS.get(id);
        if (buf == null) {
            synchronized (FETCH_BUFFERS) {
                buf = FETCH_BUFFERS.get(id);
                if (buf == null) {
                    if (FETCH_BUFFERS.size() >= MAX_ACTIVE_ASSEMBLIES) {
                        LOGGER.info("[e33chat] server media assembly {} refused: {} concurrent assemblies (max {})",
                            id, FETCH_BUFFERS.size(), MAX_ACTIVE_ASSEMBLIES);
                        failFetch(id, "too many concurrent media assemblies");
                        return ChunkOutcome.REJECTED;
                    }
                    buf = new byte[totalChunks][];
                    FETCH_BUFFERS.put(id, buf);
                }
            }
        }
        if (index >= buf.length) return ChunkOutcome.REJECTED;
        if (buf[index] == null) {
            long have = FETCH_BYTES.merge(id, (long) chunk.length, Long::sum);
            if (have > MAX_ASSEMBLY_BYTES) {
                LOGGER.info("[e33chat] server media assembly {} dropped: {} bytes exceeds {} byte cap",
                    id, have, MAX_ASSEMBLY_BYTES);
                failFetch(id, "media too large: " + id);
                return ChunkOutcome.REJECTED;
            }
        }
        FETCH_TOUCH.put(id, System.currentTimeMillis());
        buf[index] = chunk;
        int got = FETCH_COUNTS.merge(id, 1, Integer::sum);
        if (got == totalChunks) {
            FETCH_BUFFERS.remove(id);
            FETCH_COUNTS.remove(id);
            FETCH_BYTES.remove(id);
            FETCH_TOUCH.remove(id);
            CompletableFuture<byte[]> f = FETCHES.remove(id);
            int total = 0;
            boolean complete = true;
            for (byte[] c : buf) {
                if (c == null) { complete = false; break; }
                total += c.length;
            }
            if (f != null) {
                if (!complete) {
                    f.completeExceptionally(new RuntimeException("media chunk missing: " + id));
                } else if (total > DiskMediaStore.MAX_SINGLE_BYTES) {
                    f.completeExceptionally(new RuntimeException("media too large: " + id));
                } else {
                    byte[] all = new byte[total];
                    int off = 0;
                    for (byte[] c : buf) {
                        System.arraycopy(c, 0, all, off, c.length);
                        off += c.length;
                    }
                    f.complete(all);
                }
            }
            return ChunkOutcome.COMPLETE;
        }
        return ChunkOutcome.ASSEMBLING;
    }

    /**
     * Upload bytes to the server. Worker-thread only. Returns the
     * e33chat://media/<id> URL, or null on any failure (caller falls back).
     */
    public static String upload(byte[] bytes, String contentType) {
        if (!serverEnabled || bytes == null || bytes.length == 0) return null;
        long uploadId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        int totalChunks = DiskMediaStore.totalChunksFor(bytes.length);
        CompletableFuture<String> done = new CompletableFuture<>();
        UPLOADS.put(uploadId, done);
        for (int i = 0; i < totalChunks; i++) {
            int from = i * DiskMediaStore.CHUNK_BYTES;
            int len = Math.min(DiskMediaStore.CHUNK_BYTES, bytes.length - from);
            byte[] chunk = new byte[len];
            System.arraycopy(bytes, from, chunk, 0, len);
            PacketDistributor.sendToServer(new MediaUploadPayload(uploadId, i, totalChunks,
                bytes.length, contentType, chunk));
        }
        try {
            String mediaId = done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // Keep our own bytes: the local view then needs no download at all.
            if (mediaId != null) rememberOwnUpload(mediaId, bytes);
            return mediaId != null ? "e33chat://media/" + mediaId : null;
        } catch (Exception e) {
            UPLOADS.remove(uploadId);
            LOGGER.info("[e33chat] server media upload timed out after {}s", TIMEOUT_SECONDS);
            return null;
        }
    }

    /**
     * Upload bytes to the server as group-owned media. Worker-thread only
     * (blocking, like {@link #upload}). Returns the {@code e33chat://media/<id>}
     * URL, where {@code <id>} is the private {@code g_} id the server stored, or
     * null on any failure.
     *
     * <p>The group context (groupId + uploadId) is sent immediately before the
     * first chunk so the server can authorize the real connection and bind the
     * bytes to the group UUID: the file then reads only through that group's
     * rules. There is deliberately no third-party fallback here — a group image
     * must never be rehosted as a public link (2.5.0 M9 / §13 D6).
     */
    public static String uploadGroup(UUID groupId, byte[] bytes, String contentType) {
        if (!serverEnabled || groupId == null || bytes == null || bytes.length == 0) return null;
        long uploadId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        int totalChunks = DiskMediaStore.totalChunksFor(bytes.length);
        CompletableFuture<String> done = new CompletableFuture<>();
        UPLOADS.put(uploadId, done);
        PacketDistributor.sendToServer(new GroupMediaContextPayload(groupId, uploadId));
        for (int i = 0; i < totalChunks; i++) {
            int from = i * DiskMediaStore.CHUNK_BYTES;
            int len = Math.min(DiskMediaStore.CHUNK_BYTES, bytes.length - from);
            byte[] chunk = new byte[len];
            System.arraycopy(bytes, from, chunk, 0, len);
            PacketDistributor.sendToServer(new GroupMediaUploadPayload(uploadId, i, totalChunks,
                bytes.length, contentType, chunk));
        }
        try {
            String mediaId = done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (mediaId != null) rememberOwnUpload(mediaId, bytes);
            return mediaId != null ? "e33chat://media/" + mediaId : null;
        } catch (Exception e) {
            UPLOADS.remove(uploadId);
            LOGGER.info("[e33chat] group media upload timed out after {}s", TIMEOUT_SECONDS);
            return null;
        }
    }

    /** Download a server-hosted file. Worker-thread only. Returns raw bytes or null. */
    public static byte[] fetch(String mediaId) {
        if (!DiskMediaStore.isValidMediaId(mediaId)) return null;
        // Our own upload: answer from memory, so it costs no download quota and
        // can never be the thing that "randomly failed to load".
        byte[] own = OWN_UPLOADS.get(mediaId);
        if (own != null) return own;
        dropIdleAssemblies();
        // computeIfAbsent is atomic: the first caller owns the request and the
        // rest attach to the same future. This loader previously did a plain
        // put(), so the second caller (the animated probe and the static loader
        // both fetch every e33chat:// URL) replaced the first caller's future —
        // the first then waited out its full 30s timeout and reported a failure
        // for an image that had downloaded fine. The merge fix in 2.4.11 only
        // ever landed on the Forge side.
        CompletableFuture<byte[]> done = FETCHES.computeIfAbsent(mediaId, id -> {
            CompletableFuture<byte[]> fresh = new CompletableFuture<>();
            PacketDistributor.sendToServer(new MediaRequestPayload(id));
            return fresh;
        });
        try {
            return done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            FETCHES.remove(mediaId, done);
            FETCH_BUFFERS.remove(mediaId);
            FETCH_COUNTS.remove(mediaId);
            FETCH_BYTES.remove(mediaId);
            FETCH_TOUCH.remove(mediaId);
            LOGGER.info("[e33chat] server media fetch {} timed out after {}s", mediaId, TIMEOUT_SECONDS);
            return null;
        } catch (Exception e) {
            // The server answers a refused request with the same sentinel it uses
            // for a missing file, so this is nearly always the rate limit rather
            // than a timeout. Reporting it as one sent every investigation down
            // the wrong path ("it said 30s but only took 6ms").
            FETCHES.remove(mediaId, done);
            FETCH_BUFFERS.remove(mediaId);
            FETCH_COUNTS.remove(mediaId);
            FETCH_BYTES.remove(mediaId);
            FETCH_TOUCH.remove(mediaId);
            LOGGER.info("[e33chat] server media fetch {} refused: {} (most likely the per-player "
                + "transfer rate limit, 16 per 10s)", mediaId, e.getMessage());
            return null;
        }
    }

    // ---- package-private test hooks (no MC/network involved) ----

    /** Register an in-flight request as {@link #fetch} would, and return its future. */
    static CompletableFuture<byte[]> registerPendingFetchForTest(String mediaId) {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        FETCHES.put(mediaId, f);
        return f;
    }

    static int activeAssemblyCountForTest() { return FETCH_BUFFERS.size(); }

    static int pendingFetchCountForTest() { return FETCHES.size(); }

    static void clearStateForTest() {
        FETCHES.clear();
        FETCH_BUFFERS.clear();
        FETCH_COUNTS.clear();
        FETCH_BYTES.clear();
        FETCH_TOUCH.clear();
    }
}
