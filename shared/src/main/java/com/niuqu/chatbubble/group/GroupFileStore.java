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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable group metadata and invitation file. */
public final class GroupFileStore {
    private static final int SCHEMA_VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record GroupRecord(GroupIdentity identity, GroupAccess access) {
        public GroupRecord {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(access, "access");
        }
    }

    public record Snapshot(Map<UUID, GroupRecord> groups,
                           Map<UUID, GroupInvitation> invitations) {
        public Snapshot {
            Objects.requireNonNull(groups, "groups");
            Objects.requireNonNull(invitations, "invitations");
            LinkedHashMap<UUID, GroupRecord> groupCopy = new LinkedHashMap<>();
            Set<String> names = new LinkedHashSet<>();
            for (var entry : groups.entrySet()) {
                UUID id = Objects.requireNonNull(entry.getKey(), "group id");
                GroupRecord record = Objects.requireNonNull(entry.getValue(), "group record");
                if (!id.equals(record.identity().id())) {
                    throw new IllegalArgumentException("group map key must match identity ID");
                }
                if (!names.add(record.identity().displayName())) {
                    throw new IllegalArgumentException("group display names must be unique");
                }
                groupCopy.put(id, record);
            }
            LinkedHashMap<UUID, GroupInvitation> invitationCopy = new LinkedHashMap<>();
            for (var entry : invitations.entrySet()) {
                UUID id = Objects.requireNonNull(entry.getKey(), "invitation id");
                GroupInvitation invitation = Objects.requireNonNull(entry.getValue(), "invitation");
                if (!id.equals(invitation.id())) {
                    throw new IllegalArgumentException("invitation map key must match invitation ID");
                }
                if (!groupCopy.containsKey(invitation.groupId())) {
                    throw new IllegalArgumentException("invitation must refer to a current group");
                }
                invitationCopy.put(id, invitation);
            }
            groups = Collections.unmodifiableMap(groupCopy);
            invitations = Collections.unmodifiableMap(invitationCopy);
        }
    }

    private final Path file;

    public GroupFileStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    public Snapshot load() throws IOException {
        if (!Files.exists(file)) return emptySnapshot();
        byte[] originalBytes = Files.readAllBytes(file);
        String json = new String(originalBytes, StandardCharsets.UTF_8);
        try {
            FileEnvelope envelope = GSON.fromJson(json, FileEnvelope.class);
            if (envelope == null) throw new IOException("group file is empty or null");
            if (envelope.schemaVersion == null) {
                Snapshot migrated = migrateV1(json);
                preserveV1(originalBytes);
                writeV2(migrated);
                return migrated;
            }
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                throw new IOException("unsupported group file schema: " + envelope.schemaVersion);
            }
            return readV2(json);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("invalid group file", e);
        }
    }

    public void save(Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (Files.exists(file)) {
            FileEnvelope envelope = readEnvelope();
            if (envelope.schemaVersion == null) load(); // Preserve/migrate the v1 roster before replacing it.
            else if (envelope.schemaVersion != SCHEMA_VERSION) {
                throw new IOException("unsupported group file schema: " + envelope.schemaVersion);
            }
        }
        writeV2(snapshot);
    }

    private Snapshot migrateV1(String json) throws IOException {
        LegacyRoot legacy = GSON.fromJson(json, LegacyRoot.class);
        if (legacy == null || legacy.groups == null) throw new IOException("invalid v1 group file");
        LinkedHashMap<UUID, GroupRecord> groups = new LinkedHashMap<>();
        for (var entry : legacy.groups.entrySet()) {
            String name = entry.getKey();
            LegacyGroup old = entry.getValue();
            if (old == null || old.members == null || old.members.isEmpty()) continue;
            UUID owner = parseUuid(old.owner, "owner of " + name);
            LinkedHashSet<UUID> members = new LinkedHashSet<>();
            for (String value : old.members) members.add(parseUuid(value, "member of " + name));
            if (!members.contains(owner)) {
                throw new IOException("v1 owner is not a current member of " + name);
            }
            UUID groupId = UUID.randomUUID();
            LinkedHashMap<UUID, Long> visibleFrom = new LinkedHashMap<>();
            for (UUID member : members) visibleFrom.put(member, 1L);
            GroupIdentity identity = new GroupIdentity(groupId, name);
            GroupAccess access = new GroupAccess(owner, visibleFrom, members);
            groups.put(groupId, new GroupRecord(identity, access));
        }
        return new Snapshot(groups, Map.of());
    }

    private Snapshot readV2(String json) throws IOException {
        StoredRoot stored = GSON.fromJson(json, StoredRoot.class);
        if (stored == null || stored.groups == null || stored.invitations == null) {
            throw new IOException("v2 group file is missing its directories");
        }
        LinkedHashMap<UUID, GroupRecord> groups = new LinkedHashMap<>();
        for (var entry : stored.groups.entrySet()) {
            StoredGroup row = entry.getValue();
            if (row == null || row.members == null) throw new IOException("invalid v2 group row");
            UUID key = parseUuid(entry.getKey(), "group key");
            UUID id = parseUuid(row.id, "group id");
            if (!key.equals(id)) throw new IOException("group key and row ID differ");
            UUID owner = parseUuid(row.owner, "group owner");
            LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>();
            LinkedHashSet<UUID> legacyReaders = new LinkedHashSet<>();
            for (var member : row.members.entrySet()) {
                UUID memberId = parseUuid(member.getKey(), "member key");
                StoredMember memberRow = member.getValue();
                if (memberRow == null || memberRow.visibleFromSequence == null) {
                    throw new IOException("invalid member row in group " + id);
                }
                starts.put(memberId, memberRow.visibleFromSequence);
                if (memberRow.legacyHistoryReader) legacyReaders.add(memberId);
            }
            GroupIdentity identity = new GroupIdentity(id, row.name);
            GroupAccess access;
            try {
                access = new GroupAccess(owner, starts, legacyReaders);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IOException("invalid access roster in group " + id, e);
            }
            groups.put(id, new GroupRecord(identity, access));
        }
        LinkedHashMap<UUID, GroupInvitation> invitations = new LinkedHashMap<>();
        for (var entry : stored.invitations.entrySet()) {
            StoredInvitation row = entry.getValue();
            if (row == null) throw new IOException("invalid invitation row");
            UUID key = parseUuid(entry.getKey(), "invitation key");
            UUID id = parseUuid(row.id, "invitation id");
            if (!key.equals(id)) throw new IOException("invitation key and row ID differ");
            try {
                invitations.put(id, new GroupInvitation(id,
                    parseUuid(row.groupId, "invitation group"),
                    parseUuid(row.inviterId, "invitation sender"),
                    parseUuid(row.inviteeId, "invitation recipient"),
                    row.createdAtMs, row.expiresAtMs));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IOException("invalid invitation " + id, e);
            }
        }
        try {
            return new Snapshot(groups, invitations);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("invalid group file directories", e);
        }
    }

    private void writeV2(Snapshot snapshot) throws IOException {
        StoredRoot root = new StoredRoot();
        for (var entry : snapshot.groups().entrySet()) {
            GroupRecord record = entry.getValue();
            StoredGroup row = new StoredGroup();
            row.id = record.identity().id().toString();
            row.name = record.identity().displayName();
            row.owner = record.access().ownerId().toString();
            row.members = new LinkedHashMap<>();
            for (var member : record.access().visibleFromSequence().entrySet()) {
                StoredMember memberRow = new StoredMember();
                memberRow.visibleFromSequence = member.getValue();
                memberRow.legacyHistoryReader = record.access().legacyHistoryReaders().contains(member.getKey());
                row.members.put(member.getKey().toString(), memberRow);
            }
            root.groups.put(entry.getKey().toString(), row);
        }
        for (var entry : snapshot.invitations().entrySet()) {
            GroupInvitation invitation = entry.getValue();
            StoredInvitation row = new StoredInvitation();
            row.id = invitation.id().toString();
            row.groupId = invitation.groupId().toString();
            row.inviterId = invitation.inviterId().toString();
            row.inviteeId = invitation.inviteeId().toString();
            row.createdAtMs = invitation.createdAtMs();
            row.expiresAtMs = invitation.expiresAtMs();
            root.invitations.put(entry.getKey().toString(), row);
        }
        root.schemaVersion = SCHEMA_VERSION;
        byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void preserveV1(byte[] originalBytes) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".v1.bak");
        Files.write(backup, originalBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
    }

    private FileEnvelope readEnvelope() throws IOException {
        try {
            FileEnvelope envelope = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FileEnvelope.class);
            if (envelope == null) throw new IOException("group file is empty or null");
            return envelope;
        } catch (RuntimeException e) {
            throw new IOException("invalid group file", e);
        }
    }

    private static UUID parseUuid(String value, String field) throws IOException {
        try {
            return UUID.fromString(Objects.requireNonNull(value, field));
        } catch (RuntimeException e) {
            throw new IOException("invalid UUID in " + field, e);
        }
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(Map.of(), Map.of());
    }

    private static final class FileEnvelope {
        Integer schemaVersion;
    }

    private static final class LegacyRoot {
        Map<String, LegacyGroup> groups;
    }

    private static final class LegacyGroup {
        String owner;
        List<String> members;
    }

    private static final class StoredRoot {
        int schemaVersion;
        Map<String, StoredGroup> groups = new LinkedHashMap<>();
        Map<String, StoredInvitation> invitations = new LinkedHashMap<>();
    }

    private static final class StoredGroup {
        String id;
        String name;
        String owner;
        Map<String, StoredMember> members;
    }

    private static final class StoredMember {
        Long visibleFromSequence;
        boolean legacyHistoryReader;
    }

    private static final class StoredInvitation {
        String id;
        String groupId;
        String inviterId;
        String inviteeId;
        long createdAtMs;
        long expiresAtMs;
    }
}
