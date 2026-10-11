package com.niuqu.chatbubble.group;

import java.util.Objects;
import java.util.UUID;

/** Stable group ID paired with its current display name. */
public record GroupIdentity(UUID id, String displayName) {
    public GroupIdentity {
        Objects.requireNonNull(id, "id");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    public GroupIdentity renamed(String newDisplayName) {
        return new GroupIdentity(id, newDisplayName);
    }
}
