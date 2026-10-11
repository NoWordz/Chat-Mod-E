package com.niuqu.chatbubble.server;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Server-side media store for the server media hosting feature.
 *
 * Files land in <dir>/<mediaId> where mediaId is a random 32-hex UUID (no
 * dashes) — unguessable, so chat images are only reachable by players who saw
 * the URL, without adding an auth layer. Uploads stream through a per-session
 * temp file and are renamed into place on the final chunk.
 *
 * Limits: max 8 MB per file; 512 MB total quota; a per-player byte quota and a
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
 * All methods are safe to call from any thread; session state is synchronized
 * on the store instance.
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

    /** Concurrent upload sessions one player may hold open. A legit paste sends
     *  its chunks back to back, so it never needs more than one; the cap bounds
     *  how much quota a single client can reserve. */
    static final int MAX_SESSIONS_PER_PLAYER = 4;
    /** Wall-clock ceiling on one session: a client that stalls or vanishes
     *  mid-upload must not hold its quota reservation (or its player slot)
     *  forever. Swept by cleanupExpired — no separate scheduler. */
    static final long SESSION_TTL_MILLIS = 5L * 60 * 1000;

    private static final Pattern MEDIA_ID = Pattern.compile("[0-9a-f]{32}");

    /** Test/telemetry probe: number of full directory walks performed. The chunk
     *  path must never bump this (audit M11). */
    private static final AtomicInteger DIRECTORY_SCANS = new AtomicInteger();

    /** Full directory walks since class load (test probe for M11). */
    public static int directoryScanCount() {
        return DIRECTORY_SCANS.get();
    }

    private final Path dir;
    private final long maxSingle;
    private final long quota;
    private final long ttlMillis;
    private final long maxPlayerBytes;
    private final int maxFilesPerPlayer;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

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
                           long writtenBytes, long createdAtMillis) {}

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
    }

    public static String newMediaId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isValidMediaId(String id) {
        return id != null && MEDIA_ID.matcher(id).matches();
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

    /** First chunk: validate size/quota and create the session. Null on success, else error reason. */
    public synchronized String beginUpload(long uploadId, String playerName, int totalChunks,
                                           long totalBytes, String contentType) {
        bootstrapDone();
        if (totalBytes <= 0 || totalBytes > maxSingle) return "too large";
        if (totalChunks < 1 || totalChunks > totalChunksFor(maxSingle)) return "bad chunks";
        long now = System.currentTimeMillis();
        // Retire sessions this client never finished before counting slots and
        // quota: otherwise an abandoned upload blocks its owner until the next
        // throttled cleanup (up to 6h) and holds its reservation with it.
        sweepIdleSessions(now);
        // A re-used uploadId would replace the session and leak its temp file
        // and its reservation for the rest of the server's life.
        Session prior = sessions.get(uploadId);
        if (prior != null) endSession(prior, true);
        if (sessionCountFor(playerName) >= MAX_SESSIONS_PER_PLAYER) return "too many uploads";
        // Per-player file-count cap: refuse before any reservation is taken, so
        // a rejected upload consumes nothing (audit M10).
        if (playerName != null && playerFileCount(playerName) >= maxFilesPerPlayer) {
            return "too many files";
        }
        // Per-player byte quota, charged for open reservations plus committed
        // files so a single identity cannot drain the shared pool.
        if (playerName != null
                && playerReservedBytes(playerName) + playerStoredBytes.getOrDefault(playerName, 0L)
                    + totalBytes > maxPlayerBytes) {
            return "quota exceeded";
        }
        // Global quota: committed bytes + every open reservation + this one.
        if (storedBytes + reservedBytes + totalBytes > quota) return "quota exceeded";
        try {
            Files.createDirectories(dir);
            Session s = new Session(uploadId, playerName, totalChunks, totalBytes,
                contentType, dir.resolve(".upload-" + uploadId), 0, 0, now);
            sessions.put(uploadId, s);
            reservedBytes += totalBytes; // released by every endSession() call site
            return null;
        } catch (IOException e) {
            return "io";
        }
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
                s.writtenBytes() + chunk.length, s.createdAtMillis());
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
                    String id = newMediaId();
                    Files.move(tmp, dir.resolve(id));
                    recordCommitted(id, s.playerName(), actual);
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
        Session s = sessions.get(uploadId);
        if (s != null) endSession(s, true);
    }

    /** Discard every in-flight upload (server stop). Removes temp files. */
    public synchronized void discardAllUploads() {
        for (Session s : sessions.values()) deleteTemp(s.tmpFile());
        sessions.clear();
        reservedBytes = 0;
    }

    /** Discard in-flight uploads started by a player who left (disconnect). */
    public synchronized void discardUploadsFor(String playerName) {
        for (Session s : sessions.values()) {
            if (playerName != null && playerName.equals(s.playerName())) {
                endSession(s, true);
            }
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
        if (!isValidMediaId(mediaId) || !Files.isRegularFile(f)) return -1;
        try {
            return Files.size(f);
        } catch (IOException e) {
            return -1;
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
        try {
            if (!Files.isDirectory(dir)) return 0;
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    boolean orphanTmp = name.startsWith(".upload-");
                    if (!isValidMediaId(name) && !orphanTmp) continue;
                    long modified = Files.getLastModifiedTime(p).toMillis();
                    if (now - modified > ttlMillis) {
                        long size = -1;
                        if (isValidMediaId(name)) {
                            try { size = Files.size(p); } catch (IOException ignored) {}
                        }
                        Files.deleteIfExists(p);
                        if (!orphanTmp) releaseCommitted(name, size);
                        removed++;
                    }
                }
            }
        } catch (IOException ignored) {}
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

    // ---- accounting helpers -------------------------------------------------

    /**
     * One-time scan of a pre-existing directory so committed totals start correct
     * after a restart. Ownership is unknown for pre-existing files, so the
     * per-player counters only cover files stored since this instance started;
     * the global counters cover everything. Called under the monitor; never from
     * the chunk path.
     */
    private boolean bootstrapDone() {
        if (bootstrapped) return true;
        bootstrapped = true;
        try {
            if (!Files.isDirectory(dir)) return true;
            DIRECTORY_SCANS.incrementAndGet();
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
