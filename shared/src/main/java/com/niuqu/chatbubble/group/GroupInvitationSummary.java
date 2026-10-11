package com.niuqu.chatbubble.group;

import java.util.Objects;
import java.util.UUID;

/** Invitation fields safe to show only to its invitee. */
public record GroupInvitationSummary(UUID id, UUID groupId, String groupName,
                                     UUID inviterId, long createdAtMs, long expiresAtMs) {
    public GroupInvitationSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(groupName, "groupName");
        Objects.requireNonNull(inviterId, "inviterId");
        if (createdAtMs < 0 || expiresAtMs < createdAtMs) {
            throw new IllegalArgumentException("invalid invitation summary lifetime");
        }
    }
}
