package com.niuqu.chatbubble.server;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable ownership of one server-hosted file that belongs to a group (2.5.0 M9).
 *
 * <p>This is the frozen group-media authorization model: identity is the group
 * <em>UUID</em>, never a display name (master plan §13 D6 / audit §B4). There is
 * exactly one such model in the tree; the name-keyed {@code GroupMediaIndex}
 * alternative is explicitly not implemented.
 *
 * <p>A group image is uploaded before the message that references it exists, so
 * the binding starts {@link #PENDING} (no sequence yet). Once the durable group
 * row is appended, {@code sequence} is set to that row's sequence and the file
 * is readable exactly by members whose join window covers it. A file with no
 * binding at all is an ordinary public upload and keeps the pre-2.5.0 semantics
 * (anyone holding the unguessable URL), which is why a missing binding must
 * never be created retroactively for an already-public or external URL.
 */
public record MediaBinding(String mediaId, UUID groupId, UUID uploaderId, long sequence, long createdAt) {
    /** No group row references this file yet. */
    public static final long PENDING = 0L;

    public MediaBinding {
        Objects.requireNonNull(mediaId, "mediaId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(uploaderId, "uploaderId");
        if (!DiskMediaStore.isPrivateMediaId(mediaId)) {
            throw new IllegalArgumentException("mediaId must be a group-private id");
        }
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
    }

    /** Uploaded but not yet referenced by a durable group row. */
    public boolean pending() {
        return sequence == PENDING;
    }
}
