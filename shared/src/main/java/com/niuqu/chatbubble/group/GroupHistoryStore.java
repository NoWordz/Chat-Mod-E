package com.niuqu.chatbubble.group;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** Bounded durable per-group message history. */
public final class GroupHistoryStore implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_PAGE_SIZE = 100;
    private static final String SEQUENCE_INDEX = "last-sequences.json";
    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    private final Path root;
    private final Path sequenceIndex;
    private final int retentionDays;
    private final int maxMessages;
    private final long maxBytes;
    private final Map<UUID, Long> lastSequences = new LinkedHashMap<>();
    private final Map<UUID, NavigableMap<Long, StoredRow>> rowsByGroup = new LinkedHashMap<>();
    private long retainedBytes;
    private int retainedCount;
    private boolean closed;

    public GroupHistoryStore(Path root, int retentionDays, int maxMessages, long maxBytes) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.sequenceIndex = this.root.resolve(SEQUENCE_INDEX);
        if (retentionDays < 1 || maxMessages < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("group history limits must be positive");
        }
        this.retentionDays = retentionDays;
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;
        loadFromDisk();
    }

    public synchronized long lastSequence(UUID groupId) {
        return lastSequences.getOrDefault(Objects.requireNonNull(groupId, "groupId"), 0L);
    }

    /** Oldest retained sequence, or lastSequence + 1 when the group has no retained rows. */
    public synchronized long retainedFromSequence(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        NavigableMap<Long, StoredRow> rows = rowsByGroup.get(groupId);
        if (rows != null && !rows.isEmpty()) return rows.firstKey();
        return lastSequence(groupId) + 1;
    }

    public synchronized GroupHistoryEntry append(UUID groupId, UUID sender, String senderName, String content,
                                    long time, String replyContent, String replySender) throws IOException {
        requireOpen();
        long last = lastSequence(groupId);
        final long sequence;
        try {
            sequence = Math.addExact(last, 1L);
        } catch (ArithmeticException overflow) {
            throw new IOException("group sequence exhausted", overflow);
        }
        GroupHistoryEntry entry;
        try {
            entry = new GroupHistoryEntry(groupId, sequence, sender, senderName, content,
                time, replyContent, replySender);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid group history entry", invalid);
        }
        byte[] encoded = GSON.toJson(StoredEntry.from(entry)).getBytes(StandardCharsets.UTF_8);
        Path groupDirectory = root.resolve(groupId.toString());
        Path rowFile = groupDirectory.resolve(sequence + ".json");
        Files.createDirectories(groupDirectory);
        writeAtomically(rowFile, encoded);
        lastSequences.put(groupId, sequence);
        rowsByGroup.computeIfAbsent(groupId, ignored -> new TreeMap<>())
            .put(sequence, new StoredRow(entry, rowFile, encoded.length));
        retainedBytes += encoded.length;
        retainedCount++;
        writeSequenceIndex();
        prune(System.currentTimeMillis());
        return entry;
    }

    public synchronized List<GroupHistoryEntry> pageAfter(UUID groupId, long afterSequence, int limit)
            throws IOException {
        requireOpen();
        Objects.requireNonNull(groupId, "groupId");
        int cappedLimit = Math.min(Math.max(limit, 0), MAX_PAGE_SIZE);
        if (cappedLimit == 0) return List.of();
        NavigableMap<Long, StoredRow> rows = rowsByGroup.get(groupId);
        if (rows == null || rows.isEmpty()) return List.of();
        List<GroupHistoryEntry> page = new ArrayList<>(Math.min(cappedLimit, rows.size()));
        for (StoredRow row : rows.tailMap(Math.max(afterSequence, 0L), false).values()) {
            page.add(row.entry());
            if (page.size() == cappedLimit) break;
        }
        return List.copyOf(page);
    }

    public synchronized void delete(UUID groupId) throws IOException {
        requireOpen();
        Objects.requireNonNull(groupId, "groupId");
        deleteDirectory(groupId);
        NavigableMap<Long, StoredRow> rows = rowsByGroup.remove(groupId);
        if (rows != null) {
            for (StoredRow row : rows.values()) {
                retainedBytes -= row.byteSize();
                retainedCount--;
            }
        }
        lastSequences.remove(groupId);
        writeSequenceIndex();
    }

    /** Removes retained files for groups absent from the durable group roster. */
    public synchronized void retainOnly(Set<UUID> currentGroupIds) throws IOException {
        requireOpen();
        Objects.requireNonNull(currentGroupIds, "currentGroupIds");
        LinkedHashSet<UUID> known = new LinkedHashSet<>(currentGroupIds);
        LinkedHashSet<UUID> stored = new LinkedHashSet<>(lastSequences.keySet());
        stored.addAll(rowsByGroup.keySet());
        for (UUID groupId : stored) {
            if (known.contains(groupId)) continue;
            deleteDirectory(groupId);
            NavigableMap<Long, StoredRow> rows = rowsByGroup.remove(groupId);
            if (rows != null) {
                for (StoredRow row : rows.values()) {
                    retainedBytes -= row.byteSize();
                    retainedCount--;
                }
            }
            lastSequences.remove(groupId);
        }
        writeSequenceIndex();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        writeSequenceIndex();
        closed = true;
    }

    private void loadFromDisk() throws IOException {
        if (!Files.exists(root)) return;
        readSequenceIndex();
        boolean recoveredSequence = false;
        try (Stream<Path> children = Files.list(root)) {
            for (Path directory : children.toList()) {
                if (!Files.isDirectory(directory)) continue;
                UUID groupId;
                try {
                    groupId = UUID.fromString(directory.getFileName().toString());
                } catch (IllegalArgumentException notGroupDirectory) {
                    continue;
                }
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path path : files.toList()) {
                        String filename = path.getFileName().toString();
                        if (!filename.endsWith(".json")) continue;
                        long sequence;
                        try {
                            sequence = Long.parseLong(filename.substring(0, filename.length() - 5));
                        } catch (NumberFormatException notSequenceFile) {
                            continue;
                        }
                        if (sequence < 1) continue;
                        StoredEntry stored;
                        try {
                            stored = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), StoredEntry.class);
                        } catch (RuntimeException malformed) {
                            throw new IOException("invalid group history row " + path, malformed);
                        }
                        if (stored == null) throw new IOException("empty group history row " + path);
                        GroupHistoryEntry entry;
                        try {
                            entry = stored.toEntry();
                        } catch (RuntimeException invalid) {
                            throw new IOException("invalid group history row " + path, invalid);
                        }
                        if (!entry.groupId().equals(groupId) || entry.sequence() != sequence) {
                            throw new IOException("group history row path does not match its identity: " + path);
                        }
                        long size = Files.size(path);
                        rowsByGroup.computeIfAbsent(groupId, ignored -> new TreeMap<>())
                            .put(sequence, new StoredRow(entry, path, size));
                        retainedBytes += size;
                        retainedCount++;
                        long old = lastSequences.getOrDefault(groupId, 0L);
                        if (sequence > old) {
                            lastSequences.put(groupId, sequence);
                            recoveredSequence = true;
                        }
                    }
                }
            }
        }
        if (recoveredSequence) writeSequenceIndex();
        prune(System.currentTimeMillis());
    }

    private void readSequenceIndex() throws IOException {
        if (!Files.exists(sequenceIndex)) return;
        SequenceRoot index;
        try {
            index = GSON.fromJson(Files.readString(sequenceIndex, StandardCharsets.UTF_8), SequenceRoot.class);
        } catch (RuntimeException malformed) {
            throw new IOException("invalid group history sequence index", malformed);
        }
        if (index == null || index.lastSequences == null) {
            throw new IOException("group history sequence index is missing its values");
        }
        for (var entry : index.lastSequences.entrySet()) {
            try {
                UUID groupId = UUID.fromString(entry.getKey());
                if (entry.getValue() == null || entry.getValue() < 0) {
                    throw new IllegalArgumentException("invalid sequence");
                }
                lastSequences.put(groupId, entry.getValue());
            } catch (RuntimeException invalid) {
                throw new IOException("invalid group history sequence index entry", invalid);
            }
        }
    }

    private void writeSequenceIndex() throws IOException {
        SequenceRoot index = new SequenceRoot();
        for (var entry : lastSequences.entrySet()) index.lastSequences.put(entry.getKey().toString(), entry.getValue());
        Files.createDirectories(root);
        writeAtomically(sequenceIndex, GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
    }

    private void prune(long nowMs) throws IOException {
        long cutoff = nowMs - (long) retentionDays * MILLIS_PER_DAY;
        List<StoredRow> oldestFirst = new ArrayList<>();
        for (NavigableMap<Long, StoredRow> rows : rowsByGroup.values()) oldestFirst.addAll(rows.values());
        oldestFirst.sort(Comparator.comparingLong((StoredRow row) -> row.entry().time())
            .thenComparing(row -> row.entry().groupId().toString())
            .thenComparingLong(row -> row.entry().sequence()));
        for (StoredRow row : oldestFirst) {
            boolean expired = row.entry().time() < cutoff;
            boolean overCount = retainedCount > maxMessages;
            boolean overBytes = retainedBytes > maxBytes;
            if (!expired && !overCount && !overBytes) break;
            Files.deleteIfExists(row.file());
            NavigableMap<Long, StoredRow> groupRows = rowsByGroup.get(row.entry().groupId());
            if (groupRows != null && groupRows.remove(row.entry().sequence()) != null) {
                retainedCount--;
                retainedBytes -= row.byteSize();
                if (groupRows.isEmpty()) rowsByGroup.remove(row.entry().groupId());
            }
        }
    }

    private static void writeAtomically(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        try {
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("group history store is closed");
    }

    private void deleteDirectory(UUID groupId) throws IOException {
        Path directory = root.resolve(groupId.toString());
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private record StoredRow(GroupHistoryEntry entry, Path file, long byteSize) {}

    private static final class SequenceRoot {
        Map<String, Long> lastSequences = new LinkedHashMap<>();
    }

    private static final class StoredEntry {
        String groupId;
        long sequence;
        String senderUUID;
        String senderName;
        String content;
        long time;
        String replyContent;
        String replySender;

        static StoredEntry from(GroupHistoryEntry entry) {
            StoredEntry row = new StoredEntry();
            row.groupId = entry.groupId().toString();
            row.sequence = entry.sequence();
            row.senderUUID = entry.senderUUID().toString();
            row.senderName = entry.senderName();
            row.content = entry.content();
            row.time = entry.time();
            row.replyContent = entry.replyContent();
            row.replySender = entry.replySender();
            return row;
        }

        GroupHistoryEntry toEntry() {
            return new GroupHistoryEntry(UUID.fromString(groupId), sequence, UUID.fromString(senderUUID),
                senderName, content, time, replyContent, replySender);
        }
    }
}
