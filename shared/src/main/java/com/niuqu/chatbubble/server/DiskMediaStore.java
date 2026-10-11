package com.niuqu.chatbubble.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Server-side media store for the server media hosting feature.
 *
 * <p>Files land in {@code <dir>/<mediaId>}. Two id shapes exist:
 * <ul>
 *   <li>an ordinary <em>public</em> upload: a random 32-hex id (no dashes) —
 *       unguessable, so the pre-2.5.0 chat images are reachable by players who
 *       saw the URL, without an auth layer;</li>
 *   <li>a group-<em>private</em> upload (2.5.0 M9): {@code g_} + 32 hex, always
 *       accompanied by a durable {@link MediaBinding} that names the owning group
 *       UUID, so the bytes are readable only through the group rules.</li>
 * </ul>
 * Uploads stream through a per-session temp file and are renamed into place on
 * the final chunk.
 *
 * <p>Limits: max 8 MB per file; 512 MB total quota; a per-player byte quota and a
 * per-player file-count cap so one identity cannot drain the shared pool (the
 * 2.5.0 media audit M10: 64 x 8 MB uploads used to exhaust 512 MB in ~40s).
 * TTL is 7 days; cleanup only runs when the server opts in (media_auto_clean).
 *
 * <p>The quota is charged against committed files <em>plus</em> the totalBytes
 * advertised by every open session ({@link #reservedBytes}), not just what has
 * hit the disk yet: without that reservation N sessions opened while the
 * directory is empty each pass the check and together write N * 8 MB. Counts
 * are kept incrementally — no directory walk happens on the chunk path (audit
 * M11: the old {@code dirSize()} ran {@code Files.walk} while holding the store
 * monitor, so N chunk packets cost O(N * files)).
 *
 * <p>Group ownership is carried from the message path to the chunk path through
 * an upload scope keyed by the real connection UUID + uploadId (2.5.0 M9): a
 * scope whose membership check failed is remembered as denied, so the following
 * chunks can never fall through to an ordinary public save. Bindings are kept in
 * a sibling JSON file (outside the quota scan) and are written atomically.
 *
 * <p>All methods are safe to call from any thread; session, quota and binding
 * state is synchronized on the store instance.
 */
public final class DiskMediaStore {
    public static final long MAX_SINGLE_BYTES = 8L * 1024 * 1024;
    public static final long QUOTA_BYTES = 512L * 1024 * 1024;
    public static final int CHUNK_BYTES = 512 * 1024;
    public static final long TTL_MILLIS = 7L * 24 * 60 * 60 * 1000; // 7 days

    /** How many bytes one player's committed files may occupy. */
    public static final long MAX_PLAYER_BYTES = 64L * 1024 * 1024;
    /** How many committed files one player may own. */
    public static final int MAX_FILES_PER_PLAYER = 256;

    /** {@link #beginUpload} result when a first chunk duplicates a live session. */
    public static final String IN_PROGRESS = "in progress";

    /** Group-private ids carry an explicit prefix so a missing binding can never be
     *  mistaken for a legacy public file. */
    public static final String PRIVATE_PREFIX = "g_";
    private static final Pattern PUBLIC_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PRIVATE_ID = Pattern.compile("g_[0-9a-f]{32}");
    /** {@code e33chat://media/<id>} anywhere in a message body (CICode, raw URL). */
    private static final Pattern HOSTED_MEDIA_URL =
        Pattern.compile("e33chat://media/((?:g_)?[0-9a-f]{32})");

    /** Concurrent upload sessions one player may hold open. A legit paste sends
     *  its chunks back to back, so it never needs more than one; the cap bounds
     *  how much quota a single client can reserve. */
    static final int MAX_SESSIONS_PER_PLAYER = 4;
    /** Wall-clock ceiling on one session: a client that stalls or vanishes
     *  mid-upload must not hold its quota reservation (or its player slot)
     *  forever. Swept by cleanupExpired — no separate scheduler. */
    static final long SESSION_TTL_MILLIS = 5L * 60 * 1000;
    /** Bound on upload scopes awaiting their first chunk (authorized group uploads). */
    private static final int MAX_PENDING_SCOPES = 256;
    /** Bound on denied upload ids: enough to cover a refused upload's remaining chunks. */
    private static final int MAX_DENIED_UPLOADS = 256;
    /** How long a registered group scope (or a denial) stays valid. */
    private static final long SCOPE_TTL_MILLIS = 10L * 60 * 1000;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BINDINGS_SUFFIX = ".bindings.json";

    private final Path dir;
    private final long maxSingle;
    private final long quota;
    private final long ttlMillis;
    private final long maxPlayerBytes;
    private final int maxFilesPerPlayer;
    private final Path bindingsFile;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, MediaBinding> bindings = new ConcurrentHashMap<>();

    /**
     * Set when the binding file existed but could not be read (IO error, invalid
     * JSON, or not the array shape {@link #persistBindings()} writes). The map
     * stays empty (fail-closed: every {@code g_} id is refused) and no write may
     * replace the on-disk file, so an unreadable-but-intact file is never
     * clobbered with the empty/cleared map. Cleared only by a restart.
     */
    private boolean bindingsUnreadable;

    /** Upload scope registered before the first chunk: authorized group, or denied. */
    private record UploadScope(String playerName, UUID groupId, UUID uploaderId, long createdAt) {}
    private final Map<Long, UploadScope> scopes = new LinkedHashMap<>();
    /** uploadId -> deniedAt. Never consumed by a single chunk: a repeated index 0
     *  stays denied for the TTL, so a refused context cannot be retried into an
     *  ordinary public save. */
    private final Map<Long, Long> deniedUploads = new LinkedHashMap<>();

    /** Sum of the advertised totalBytes of every open session. Guarded by the
     *  store monitor, like {@link #sessions} mutations. */
    private long reservedBytes;

    // ---- incremental accounting (no directory walk on the hot path) ----
    /** Committed (renamed-into-place) bytes and file count under {@link #dir}. */
    private long storedBytes;
    private int storedFiles;
    /** Committed bytes and file count per player; best-effort across restarts
     *  because ownership is not persisted. */
    private final Map<String, Long> playerStoredBytes = new ConcurrentHashMap<>();
    private final Map<String, Integer> playerStoredFiles = new ConcurrentHashMap<>();
    /** mediaId -> owning player, so a TTL delete can release the right counters. */
    private final Map<String, String> fileOwners = new ConcurrentHashMap<>();
    /** Lazily built once from an existing directory; guarded by the monitor. */
    private boolean bootstrapped;

    private record Session(long uploadId, String playerName, int totalChunks,
                           long totalBytes, String contentType, Path tmpFile, int received,
                           long writtenBytes, UUID groupId, UUID uploaderId, long createdAtMillis) {}

    public DiskMediaStore(Path dir) {
        this(dir, MAX_SINGLE_BYTES, QUOTA_BYTES, TTL_MILLIS, MAX_PLAYER_BYTES, MAX_FILES_PER_PLAYER);
    }

    /** Test hook: injectable limits (default per-player limits). */
    DiskMediaStore(Path dir, long maxSingle, long quota, long ttlMillis) {
        this(dir, maxSingle, quota, ttlMillis, MAX_PLAYER_BYTES, MAX_FILES_PER_PLAYER);
    }

    /** Test hook: injectable limits including the per-player caps. */
    DiskMediaStore(Path dir, long maxSingle, long quota, long ttlMillis,
                   long maxPlayerBytes, int maxFilesPerPlayer) {
        this.dir = dir;
        this.maxSingle = maxSingle;
        this.quota = quota;
        this.ttlMillis = ttlMillis;
        this.maxPlayerBytes = maxPlayerBytes;
        this.maxFilesPerPlayer = maxFilesPerPlayer;
        Path name = dir.getFileName();
        this.bindingsFile = name == null ? dir.resolve(BINDINGS_SUFFIX)
            : dir.resolveSibling(name + BINDINGS_SUFFIX);
        loadBindings();
    }

    public static String newMediaId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** A group-private id: an ordinary public upload never produces one. */
    public static String newPrivateMediaId() {
        return PRIVATE_PREFIX + newMediaId();
    }

    /** An ordinary public upload id (32 hex, the pre-2.5.0 shape). */
    public static boolean isPublicMediaId(String id) {
        return id != null && PUBLIC_ID.matcher(id).matches();
    }

    /** A group-private upload id ({@code g_} + 32 hex). */
    public static boolean isPrivateMediaId(String id) {
        return id != null && PRIVATE_ID.matcher(id).matches();
    }

    /** Either kind: the only ids that may name a stored file. */
    public static boolean isStoredMediaId(String id) {
        return isPublicMediaId(id) || isPrivateMediaId(id);
    }

    /**
     * Kept for the existing client fetch/URL-validation call sites, which live
     * outside the M9 media-hosting seam: a stored file now has two shapes, so
     * this accepts both. Equivalent to {@link #isStoredMediaId}.
     */
    public static boolean isValidMediaId(String id) {
        return isStoredMediaId(id);
    }

    public static int totalChunksFor(long size) {
        return (int) ((size + CHUNK_BYTES - 1) / CHUNK_BYTES);
    }

    /** Upper bound on a legitimately advertised chunk count, derived from the
     *  per-file limit. The client uses this to bound its reassembly array: the
     *  count arrives off the wire, so an unclamped value lets one hostile
     *  response allocate an arbitrarily large array. */
    public static int maxTotalChunks() {
        return totalChunksFor(MAX_SINGLE_BYTES);
    }

    /** Whether a wire-advertised chunk count is plausible for one file. */
    public static boolean isValidChunkCount(int totalChunks) {
        return totalChunks >= 1 && totalChunks <= maxTotalChunks();
    }

    /**
     * Every server-hosted media id referenced by {@code text}, in order.
     * Used to bind a group message's images to that message's sequence.
     */
    public static List<String> hostedMediaIds(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>();
        Matcher m = HOSTED_MEDIA_URL.matcher(text);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    // ==== upload scopes (group authorization carried to the chunk path) ====

    /**
     * Register the group context for a real connection UUID + uploadId before
     * its first chunk arrives. Bounded: the oldest pending scope is dropped and
     * denied, so a scope can never silently expire into a public save.
     */
    public synchronized void noteGroupUpload(long uploadId, String playerName,
                                             UUID groupId, UUID uploaderId) {
        denyReleaseLocked(uploadId);
        scopes.remove(uploadId);
        if (scopes.size() >= MAX_PENDING_SCOPES) {
            Long eldest = scopes.keySet().iterator().next();
            scopes.remove(eldest);
            denyLocked(eldest);
        }
        scopes.put(uploadId, new UploadScope(playerName, groupId, uploaderId,
            System.currentTimeMillis()));
    }

    /**
     * The fresh scope registered for this uploadId, or null when it is an ordinary
     * public upload, was already consumed by {@link #beginUpload}, or has expired
     * (an expired scope is turned into a denial, so it can never become public).
     */
    public synchronized UploadScopeInfo groupUploadScope(long uploadId) {
        UploadScope s = scopes.get(uploadId);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.createdAt() > SCOPE_TTL_MILLIS) {
            scopes.remove(uploadId);
            denyLocked(uploadId);
            return null;
        }
        return new UploadScopeInfo(s.playerName(), s.groupId(), s.uploaderId());
    }

    /** Refuse this uploadId: its chunks must never be stored, now or after a retry. */
    public synchronized void refuseUpload(long uploadId) {
        scopes.remove(uploadId);
        denyLocked(uploadId);
    }

    /** Whether this uploadId was refused (still within the denial TTL). */
    public synchronized boolean isUploadDenied(long uploadId) {
        pruneDeniedLocked();
        return deniedUploads.containsKey(uploadId);
    }

    private void denyLocked(long uploadId) {
        deniedUploads.remove(uploadId);
        deniedUploads.put(uploadId, System.currentTimeMillis());
        while (deniedUploads.size() > MAX_DENIED_UPLOADS) {
            deniedUploads.remove(deniedUploads.keySet().iterator().next());
        }
    }

    private void denyReleaseLocked(long uploadId) {
        deniedUploads.remove(uploadId);
    }

    private void pruneDeniedLocked() {
        long now = System.currentTimeMillis();
        deniedUploads.entrySet().removeIf(e -> now - e.getValue() > SCOPE_TTL_MILLIS);
    }

    /** Read-only view of a registered upload scope. */
    public record UploadScopeInfo(String playerName, UUID groupId, UUID uploaderId) {}

    // ==== upload sessions ====

    /** First chunk: validate size/quota and create the session. Null on success, else error reason. */
    public synchronized String beginUpload(long uploadId, String playerName, int totalChunks,
                                           long totalBytes, String contentType) {
        bootstrapDone();
        // A refused uploadId stays refused for the TTL: an earlier context was
        // denied (or expired), so no later first chunk may create a session.
        if (isUploadDenied(uploadId)) return "group denied";
        if (sessions.containsKey(uploadId)) {
            // A second first chunk must never replace an in-progress session: the
            // session carries the group scope, and replacing it would drop that
            // binding and let the remaining chunks be stored as a public file.
            return IN_PROGRESS;
        }
        long now = System.currentTimeMillis();
        // Retire sessions this client never finished before counting slots and
        // quota: otherwise an abandoned upload blocks its owner until the next
        // throttled cleanup (up to 6h) and holds its reservation with it.
        sweepIdleSessions(now);
        UploadScope scope = scopes.get(uploadId);
        if (scope != null && now - scope.createdAt() > SCOPE_TTL_MILLIS) {
            scopes.remove(uploadId);
            denyLocked(uploadId);
            return "group denied";
        }
        if (scope != null && !scope.playerName().equals(playerName)) {
            // A different connection reusing this uploadId: refuse rather than
            // let somebody else's group scope authorize it.
            scopes.remove(uploadId);
            denyLocked(uploadId);
            return "group denied";
        }
        // A failed check for a scoped upload must not silently declassify it: the
        // uploadId is denied so the remaining chunks can never be stored publicly.
        if (totalBytes <= 0 || totalBytes > maxSingle) return scopeFailure(uploadId, scope, "too large");
        if (totalChunks < 1 || totalChunks > totalChunksFor(maxSingle)) {
            return scopeFailure(uploadId, scope, "bad chunks");
        }
        if (sessionCountFor(playerName) >= MAX_SESSIONS_PER_PLAYER) {
            return scopeFailure(uploadId, scope, "too many uploads");
        }
        // Per-player file-count cap: refuse before any reservation is taken, so
        // a rejected upload consumes nothing (audit M10).
        if (playerName != null && playerFileCount(playerName) >= maxFilesPerPlayer) {
            return scopeFailure(uploadId, scope, "too many files");
        }
        // Per-player byte quota, charged for open reservations plus committed
        // files so a single identity cannot drain the shared pool.
        if (playerName != null
                && playerReservedBytes(playerName) + playerStoredBytes.getOrDefault(playerName, 0L)
                    + totalBytes > maxPlayerBytes) {
            return scopeFailure(uploadId, scope, "quota exceeded");
        }
        // Global quota: committed bytes + every open reservation + this one.
        if (storedBytes + reservedBytes + totalBytes > quota) {
            return scopeFailure(uploadId, scope, "quota exceeded");
        }
        scopes.remove(uploadId); // consumed: it must not authorize a later uploadId reuse
        try {
            Files.createDirectories(dir);
            Session s = new Session(uploadId, playerName, totalChunks, totalBytes,
                contentType, dir.resolve(".upload-" + uploadId), 0, 0,
                scope != null ? scope.groupId() : null,
                scope != null ? scope.uploaderId() : null, now);
            sessions.put(uploadId, s);
            reservedBytes += totalBytes; // released by every endSession() call site
            return null;
        } catch (IOException e) {
            return "io";
        }
    }

    /** Turn a rejected scoped upload into a remembered denial so its remaining
     *  chunks cannot be retried into a public store; a public upload is unchanged. */
    private String scopeFailure(long uploadId, UploadScope scope, String reason) {
        if (scope != null) {
            scopes.remove(uploadId);
            denyLocked(uploadId);
        }
        return reason;
    }

    /**
     * Subsequent chunk: append to the session temp file. Returns null while
     * the upload is incomplete; on the final chunk returns the new mediaId
     * (success) or an error reason (failure, session discarded).
     *
     * <p>O(1) in the number of stored files: the quota is answered from the
     * incremental counters, never from a directory walk (audit M11).
     */
    public synchronized String acceptChunk(long uploadId, int index, byte[] chunk) {
        bootstrapDone();
        Session s = sessions.get(uploadId);
        if (s == null) return null;
        if (index != s.received()) {
            endSession(s, true);
            return "chunk out of order";
        }
        if (s.writtenBytes() + chunk.length > s.totalBytes()) {
            // End the session instead of writing: the client already advertised
            // totalBytes, and the excess would only be caught on the last chunk
            // after it had been written to disk.
            endSession(s, true);
            return "size mismatch";
        }
        // Defence in depth: the reservation granted at beginUpload already
        // covers s, so this only trips when something outside the store grew
        // the directory after that check.
        if (storedBytes + reservedBytes > quota) {
            endSession(s, true);
            return "quota exceeded";
        }
        try {
            Path tmp = s.tmpFile();
            try (FileOutputStream out = new FileOutputStream(tmp.toFile(), true)) {
                out.write(chunk);
            }
            Session next = new Session(s.uploadId(), s.playerName(), s.totalChunks(),
                s.totalBytes(), s.contentType(), tmp, s.received() + 1,
                s.writtenBytes() + chunk.length, s.groupId(), s.uploaderId(), s.createdAtMillis());
            sessions.put(uploadId, next);
            if (next.received() == next.totalChunks()) {
                // Terminal chunk: the session ends whatever the outcome, so the
                // reservation goes now, but the temp file is kept for the size
                // check and the rename into place.
                endSession(next, false);
                try {
                    long actual = Files.size(tmp);
                    if (actual != s.totalBytes()) {
                        deleteTemp(tmp);
                        return "size mismatch";
                    }
                    boolean group = s.groupId() != null;
                    String id = group ? newPrivateMediaId() : newMediaId();
                    Files.move(tmp, dir.resolve(id));
                    recordCommitted(id, s.playerName(), actual);
                    if (group && !bindPending(id, s.groupId(), s.uploaderId())) {
                        // Cannot durably mark this file group-private: delete it
                        // rather than leave unbound bytes that anyone with the
                        // URL could read.
                        Files.deleteIfExists(dir.resolve(id));
                        releaseCommitted(id, actual);
                        return "io";
                    }
                    return id;
                } catch (IOException e) {
                    deleteTemp(tmp);
                    return "io";
                }
            }
            return null;
        } catch (IOException e) {
            endSession(s, true);
            return "io";
        }
    }

    public synchronized void discardUpload(long uploadId) {
        scopes.remove(uploadId);
        Session s = sessions.get(uploadId);
        if (s != null) endSession(s, true);
    }

    /** Discard every in-flight upload (server stop). Removes temp files. */
    public synchronized void discardAllUploads() {
        for (Session s : sessions.values()) deleteTemp(s.tmpFile());
        sessions.clear();
        reservedBytes = 0;
        scopes.clear();
        deniedUploads.clear();
    }

    /** Discard in-flight uploads started by a player who left (disconnect). */
    public synchronized void discardUploadsFor(String playerName) {
        for (Session s : sessions.values()) {
            if (playerName != null && playerName.equals(s.playerName())) {
                endSession(s, true);
            }
        }
        if (playerName != null) {
            scopes.entrySet().removeIf(e -> playerName.equals(e.getValue().playerName()));
        }
    }

    // 4 per 10s was tight enough that ordinary use tripped it: every image costs
    // an upload plus a download, and the sender's own images were the first to
    // be refused (they are the ones being looked at right after pasting). 16
    // still bounds abuse without punishing a normal paste-a-few-photos session.
    private static final int RATE_LIMIT_PER_WINDOW = 16;
    private static final long RATE_WINDOW_MS = 10_000;
    private final Map<String, java.util.ArrayDeque<Long>> rateWindows = new ConcurrentHashMap<>();

    /**
     * Per-player sliding-window throttle for media transfers (one upload session
     * or one download request = one call). Call once per upload session (index 0),
     * not per chunk.
     */
    public boolean allowTransfer(String playerName) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> q = rateWindows.computeIfAbsent(playerName, k -> new java.util.ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > RATE_WINDOW_MS) q.removeFirst();
            if (q.size() >= RATE_LIMIT_PER_WINDOW) return false;
            q.addLast(now);
            return true;
        }
    }

    /** Size in bytes of a stored file, or -1 when absent. */
    public long sizeOf(String mediaId) {
        Path f = dir.resolve(mediaId);
        if (!isStoredMediaId(mediaId) || !Files.isRegularFile(f)) return -1;
        try {
            return Files.size(f);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Open one read handle for a whole download. Null when the file is absent or
     * empty. The returned stream is the single open handle for that transfer: the
     * caller reads it chunk by chunk and closes it exactly once.
     */
    public MediaStream openDownload(String mediaId) {
        if (!isStoredMediaId(mediaId)) return null;
        Path f = dir.resolve(mediaId);
        if (!Files.isRegularFile(f)) return null;
        try {
            long size = Files.size(f);
            if (size <= 0) return null;
            return new MediaStream(mediaId, Files.newInputStream(f), size);
        } catch (IOException e) {
            return null;
        }
    }

    /** One chunk of a stored file, or null when absent/unreadable. */
    public byte[] readChunk(String mediaId, int index, int totalChunks) {
        long size = sizeOf(mediaId);
        if (size < 0 || index < 0 || index >= totalChunks) return null;
        long start = (long) index * CHUNK_BYTES;
        if (start >= size) return null;
        int len = (int) Math.min(CHUNK_BYTES, size - start);
        try (InputStream in = Files.newInputStream(dir.resolve(mediaId))) {
            in.skipNBytes(start);
            byte[] out = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(out, off, len - off);
                if (r < 0) break;
                off += r;
            }
            return off == len ? out : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ==== durable group bindings (2.5.0 M9, UUID-keyed) ====

    /** The group binding for a stored file, or null for an ordinary public upload. */
    public MediaBinding binding(String mediaId) {
        return mediaId == null ? null : bindings.get(mediaId);
    }

    /**
     * Durably mark a just-stored file as group-owned and still unreferenced.
     * Returns false (leaving no binding and no file responsibility shift) when
     * the metadata could not be written, so the caller deletes the file instead
     * of serving bytes that would be readable by anyone holding the URL.
     */
    public synchronized boolean bindPending(String mediaId, UUID groupId, UUID uploaderId) {
        MediaBinding b = new MediaBinding(mediaId, groupId, uploaderId,
            MediaBinding.PENDING, System.currentTimeMillis());
        MediaBinding previous = bindings.put(mediaId, b);
        if (persistBindings()) return true;
        if (previous == null) bindings.remove(mediaId); else bindings.put(mediaId, previous);
        return false;
    }

    /**
     * Bind a referenced file to the durable group row's sequence. Throws when the
     * metadata cannot be written: the live session keeps the stricter committed
     * rule either way, and the caller must not report a silent success.
     */
    public synchronized void commitBinding(String mediaId, long sequence) throws IOException {
        MediaBinding b = bindings.get(mediaId);
        if (b == null || !b.pending() || sequence <= 0) return;
        MediaBinding committed = new MediaBinding(b.mediaId(), b.groupId(), b.uploaderId(),
            sequence, b.createdAt());
        bindings.put(mediaId, committed);
        if (!persistBindings()) {
            throw new IOException("could not persist media binding for " + mediaId);
        }
    }

    /**
     * Delete every hosted file belonging to a dissolved group and forget its
     * bindings. Deleting the bytes is what makes the group's media unreachable;
     * merely dropping a binding would declassify the file as public.
     */
    public synchronized int deleteGroupMedia(UUID groupId) {
        if (groupId == null) return 0;
        List<String> ids = new ArrayList<>();
        for (MediaBinding b : bindings.values()) {
            if (b.groupId().equals(groupId)) ids.add(b.mediaId());
        }
        int removed = 0;
        for (String id : ids) {
            Path f = dir.resolve(id);
            long size = 0;
            try {
                size = Files.isRegularFile(f) ? Files.size(f) : 0;
                boolean deleted = Files.deleteIfExists(f);
                if (deleted) {
                    removed++;
                    releaseCommitted(id, size);
                }
                if (deleted || !Files.exists(f)) bindings.remove(id);
                // An undeletable file must stay denied: keep its binding rather
                // than declassify it into a public upload.
            } catch (IOException e) {
                // Keep the binding (fail closed) and keep counting the bytes.
            }
        }
        if (!ids.isEmpty()) persistBindings();
        return removed;
    }

    private void loadBindings() {
        bindings.clear();
        if (!Files.isRegularFile(bindingsFile)) return;
        try {
            String json = Files.readString(bindingsFile, StandardCharsets.UTF_8);
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                // Not the shape we write: treat it like a failed read so the file
                // is preserved instead of overwritten from a half-understood state.
                bindingsUnreadable = true;
                LOGGER.warn("[e33chat] Media bindings file {} is not a JSON array; keeping it and refusing every group-owned file", bindingsFile);
                return;
            }
            for (com.google.gson.JsonElement element : root.getAsJsonArray()) {
                // Per-entry: one malformed row must not discard every binding and
                // silently turn the other group files into public uploads.
                try {
                    MediaBinding b = GSON.fromJson(element, MediaBinding.class);
                    if (b == null || !isPrivateMediaId(b.mediaId())) continue;
                    bindings.put(b.mediaId(), b);
                } catch (RuntimeException ignored) {
                }
            }
        } catch (Exception e) {
            // Keep the original file in place and refuse to write any binding for
            // this run: replacing a file we could not read with the empty map would
            // declassify every group's media at once. Fail closed until restart.
            bindingsUnreadable = true;
            bindings.clear();
            LOGGER.warn("[e33chat] Failed to read media bindings from {}; keeping the file and refusing every group-owned file until it is fixed", bindingsFile, e);
        }
    }

    private boolean persistBindings() {
        if (bindingsUnreadable) {
            LOGGER.warn("[e33chat] Refusing to overwrite unreadable media bindings file {}", bindingsFile);
            return false;
        }
        try {
            Path parent = bindingsFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            // A path that exists but is not a regular file (a directory, say) can
            // never be our JSON document: refuse rather than clobber it, and let
            // the caller delete the unbound bytes (fail closed).
            if (Files.exists(bindingsFile) && !Files.isRegularFile(bindingsFile)) return false;
            Path tmp = bindingsFile.resolveSibling(bindingsFile.getFileName() + ".tmp");
            try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                GSON.toJson(new ArrayList<>(bindings.values()), writer);
            }
            try {
                Files.move(tmp, bindingsFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, bindingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Remove files older than the TTL (no-op when TTL is 0). Returns files removed. */
    public int cleanupExpired() {
        return cleanupExpired(System.currentTimeMillis());
    }

    synchronized int cleanupExpired(long now) {
        // Runs even when file TTL cleanup is off (ttlMillis <= 0): the session
        // sweep is what bounds how long an abandoned upload can hold its
        // reservation. This is the existing cleanup path — no extra scheduler.
        sweepIdleSessions(now);
        if (ttlMillis <= 0) return 0;
        int removed = 0;
        boolean bindingsChanged = false;
        try {
            if (!Files.isDirectory(dir)) return 0;
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    boolean orphanTmp = name.startsWith(".upload-");
                    if (!isStoredMediaId(name) && !orphanTmp) continue;
                    long modified = Files.getLastModifiedTime(p).toMillis();
                    if (now - modified > ttlMillis) {
                        long size = -1;
                        if (isStoredMediaId(name)) {
                            try { size = Files.size(p); } catch (IOException ignored) {}
                        }
                        Files.deleteIfExists(p);
                        if (!orphanTmp) {
                            releaseCommitted(name, size);
                            // A group file expiring must lose its binding too, or a
                            // later upload could reuse the id shape and inherit it.
                            if (bindings.remove(name) != null) bindingsChanged = true;
                        }
                        removed++;
                    }
                }
            }
        } catch (IOException ignored) {}
        if (bindingsChanged) persistBindings();
        return removed;
    }

    private static final long CLEAN_INTERVAL_MILLIS = 6L * 60 * 60 * 1000;
    private volatile long lastCleanupAt;

    /** cleanupExpired with a 6h throttle — safe to call on every finished upload. */
    public void cleanupExpiredThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupAt < CLEAN_INTERVAL_MILLIS) return;
        lastCleanupAt = now;
        cleanupExpired(now);
    }

    /** Incremental accounting: current stored bytes (completed files + temp files). */
    synchronized long usedBytes() {
        return storedBytes;
    }

    /** Bytes currently reserved by in-flight uploads. */
    synchronized long reservedBytes() {
        return reservedBytes;
    }

    // ---- accounting helpers -------------------------------------------------

    /**
     * One-time scan of a pre-existing directory so committed totals start correct
     * after a restart. Ownership is unknown for pre-existing files, so the
     * per-player counters only cover files stored since this instance started;
     * the global counters cover everything. Called under the monitor; never from
     * the chunk path. A test wraps {@link #dir} in a counting filesystem
     * ({@code CountingFileSystem}) to prove the chunk path does not walk.
     */
    private boolean bootstrapDone() {
        if (bootstrapped) return true;
        bootstrapped = true;
        try {
            if (!Files.isDirectory(dir)) return true;
            try (var stream = Files.walk(dir)) {
                long[] bytes = {0};
                int[] files = {0};
                stream.forEach(p -> {
                    if (Files.isRegularFile(p)
                            && !p.getFileName().toString().startsWith(".upload-")) {
                        try { bytes[0] += Files.size(p); files[0]++; } catch (IOException ignored) {}
                    }
                });
                storedBytes += bytes[0];
                storedFiles += files[0];
            }
        } catch (IOException ignored) {}
        return true;
    }

    /** Committed counters one player currently holds (files). */
    private long playerFileCount(String playerName) {
        return playerStoredFiles.getOrDefault(playerName, 0);
    }

    private long playerReservedBytes(String playerName) {
        long sum = 0;
        for (Session s : sessions.values()) {
            if (playerName != null && playerName.equals(s.playerName())) sum += s.totalBytes();
        }
        return sum;
    }

    private void recordCommitted(String mediaId, String playerName, long bytes) {
        storedBytes += bytes;
        storedFiles++;
        if (playerName != null) {
            fileOwners.put(mediaId, playerName);
            playerStoredBytes.merge(playerName, bytes, Long::sum);
            playerStoredFiles.merge(playerName, 1, Integer::sum);
        }
    }

    /** Release the counters held by a committed file that is going away.
     *  {@code bytes} is the size captured before deletion (-1 when unknown). */
    private void releaseCommitted(String mediaId, long bytes) {
        String owner = fileOwners.remove(mediaId);
        if (bytes < 0) return;
        storedBytes = Math.max(0, storedBytes - bytes);
        storedFiles = Math.max(0, storedFiles - 1);
        if (owner != null) {
            playerStoredBytes.computeIfPresent(owner, (k, v) -> Math.max(0L, v - bytes));
            playerStoredFiles.computeIfPresent(owner, (k, v) -> Math.max(0, v - 1));
        }
    }

    /** Open sessions one player holds. A null name never matches, like the
     *  discard path. */
    private int sessionCountFor(String playerName) {
        if (playerName == null) return 0;
        int n = 0;
        for (Session s : sessions.values()) {
            if (playerName.equals(s.playerName())) n++;
        }
        return n;
    }

    /**
     * The single place a session ends and its reservation is released, so
     * {@link #reservedBytes} stays in step with {@link #sessions}. Every
     * session-end path funnels through here: completion, size/rename failure,
     * a re-used uploadId, an out-of-order chunk, an append IOException, a
     * player disconnect, a server stop and the idle sweep. The two-arg remove
     * means a stale Session snapshot cannot release a reservation owned by its
     * successor: a reservation is never freed twice and never leaked.
     */
    private void endSession(Session s, boolean deleteTempFile) {
        if (sessions.remove(s.uploadId(), s)) reservedBytes -= s.totalBytes();
        if (deleteTempFile) deleteTemp(s.tmpFile());
    }

    /** Drops sessions older than SESSION_TTL_MILLIS; caller holds the store
     *  monitor (beginUpload / cleanupExpired). */
    private void sweepIdleSessions(long now) {
        for (Session s : sessions.values()) {
            if (now - s.createdAtMillis() > SESSION_TTL_MILLIS) endSession(s, true);
        }
    }

    private static void deleteTemp(Path tmp) {
        try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
    }
}
