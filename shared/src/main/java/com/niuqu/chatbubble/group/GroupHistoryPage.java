package com.niuqu.chatbubble.group;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One bounded server-authorized page of durable group history. */
public record GroupHistoryPage(UUID groupId, long nextSequence, boolean hasMore,
                               List<GroupHistoryEntry> entries) {
    public GroupHistoryPage {
        Objects.requireNonNull(groupId, "groupId");
        entries = List.copyOf(entries);
        if (nextSequence < 0 || entries.size() > 100) {
            throw new IllegalArgumentException("invalid group history page bounds");
        }
        long previous = -1;
        for (GroupHistoryEntry entry : entries) {
            if (!groupId.equals(entry.groupId()) || entry.sequence() <= previous) {
                throw new IllegalArgumentException("page rows must be ordered and belong to its group");
            }
            previous = entry.sequence();
        }
    }
}
