package com.niuqu.chatbubble.group;

import java.util.Objects;
import java.util.UUID;

/** One durable group message, identified independently from its display name. */
public record GroupHistoryEntry(UUID groupId, long sequence, UUID senderUUID, String senderName,
                                String content, long time, String replyContent, String replySender) {
    public GroupHistoryEntry {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(senderUUID, "senderUUID");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(content, "content");
        if (sequence < 1 || time < 0) throw new IllegalArgumentException("invalid group history position");
    }
}
