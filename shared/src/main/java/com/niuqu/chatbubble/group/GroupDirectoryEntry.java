package com.niuqu.chatbubble.group;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Recipient-visible summary for one group the recipient currently belongs to. */
public record GroupDirectoryEntry(GroupIdentity identity, UUID ownerId, List<UUID> memberIds,
                                  long visibleFromSequence, boolean legacyHistoryAllowed,
                                  long latestSequence, long retainedFromSequence) {
    public GroupDirectoryEntry {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ownerId, "ownerId");
        memberIds = List.copyOf(memberIds);
        if (!memberIds.contains(ownerId) || visibleFromSequence < 1 || latestSequence < 0
                || retainedFromSequence < 1) {
            throw new IllegalArgumentException("invalid member group summary");
        }
    }
}
