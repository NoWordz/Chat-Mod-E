package com.niuqu.chatbubble.group;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable membership and history-read permissions for one group. */
public final class GroupAccess {
    private final UUID ownerId;
    private final Map<UUID, Long> visibleFromSequence;
    private final Set<UUID> legacyHistoryReaders;

    public GroupAccess(UUID ownerId, Map<UUID, Long> visibleFromSequence,
                       Set<UUID> legacyHistoryReaders) {
        if (ownerId == null || visibleFromSequence == null || legacyHistoryReaders == null) {
            throw new NullPointerException("group access fields must not be null");
        }
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>();
        for (var entry : visibleFromSequence.entrySet()) {
            UUID memberId = entry.getKey();
            Long sequence = entry.getValue();
            if (memberId == null || sequence == null || sequence < 1) {
                throw new IllegalArgumentException("member IDs and start sequences must be valid");
            }
            starts.put(memberId, sequence);
        }
        LinkedHashSet<UUID> legacyReaders = new LinkedHashSet<>(legacyHistoryReaders);
        if (!starts.containsKey(ownerId)) {
            throw new IllegalArgumentException("owner must be a current member");
        }
        if (!starts.keySet().containsAll(legacyReaders)) {
            throw new IllegalArgumentException("legacy readers must be current members");
        }
        this.ownerId = ownerId;
        this.visibleFromSequence = Collections.unmodifiableMap(starts);
        this.legacyHistoryReaders = Collections.unmodifiableSet(legacyReaders);
    }

    public UUID ownerId() { return ownerId; }

    public Map<UUID, Long> visibleFromSequence() { return visibleFromSequence; }

    public Set<UUID> legacyHistoryReaders() { return legacyHistoryReaders; }

    public boolean isMember(UUID playerId) { return visibleFromSequence.containsKey(playerId); }

    public boolean isOwner(UUID playerId) { return ownerId.equals(playerId); }

    /** Owner must transfer ownership or disband before leaving. */
    public boolean canLeave(UUID playerId) { return isMember(playerId) && !isOwner(playerId); }

    /** A member can read messages starting at their inclusive join sequence. */
    public boolean canRead(UUID playerId, long sequence) {
        Long start = visibleFromSequence.get(playerId);
        return start != null && sequence >= start;
    }

    /** Legacy rows remain visible only to current members granted during migration. */
    public boolean canReadLegacy(UUID playerId) {
        return isMember(playerId) && legacyHistoryReaders.contains(playerId);
    }
}
