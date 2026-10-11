package com.niuqu.chatbubble.group;

import java.util.Objects;
import java.util.UUID;

/** One recipient-scoped invitation. */
public record GroupInvitation(UUID id, UUID groupId, UUID inviterId, UUID inviteeId,
                              long createdAtMs, long expiresAtMs) {
    public GroupInvitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(inviterId, "inviterId");
        Objects.requireNonNull(inviteeId, "inviteeId");
        if (createdAtMs < 0 || expiresAtMs < createdAtMs) {
            throw new IllegalArgumentException("invitation expiry must not precede creation");
        }
    }

    public boolean isExpired(long nowMs) {
        return nowMs >= expiresAtMs;
    }
}
