package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.server.MediaService;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * Client -&gt; server: the group a media upload belongs to, sent immediately before
 * the first {@link MediaUploadPayload} chunk of the same uploadId (2.5.0 M9
 * group media).
 *
 * <p>The actor is the real receiving connection, never a value off the wire: the
 * server checks that connection's current group membership and records the scope
 * for that connection UUID + uploadId. If the group is gone or the sender is not
 * (or no longer) a member, the scope is remembered as denied so the following
 * chunks are refused instead of being stored as an ordinary public upload.
 *
 * <p>The group is named by its stable <em>UUID</em> (the frozen model, §13 D6).
 */
public record GroupMediaContextPayload(UUID groupId, long uploadId) implements CustomPayload {

    public static final CustomPayload.Id<GroupMediaContextPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_media_context_v2"));

    public static final PacketCodec<PacketByteBuf, GroupMediaContextPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeUuid(value.groupId() != null ? value.groupId() : new UUID(0, 0));
            buf.writeLong(value.uploadId());
        },
        buf -> new GroupMediaContextPayload(buf.readUuid(), buf.readLong())
    );

    @Override
    public Id<GroupMediaContextPayload> getId() { return ID; }

    public static void handleServer(GroupMediaContextPayload payload, ServerPlayerEntity player) {
        MediaService.prepareGroupUploadContext(player, payload.groupId(), payload.uploadId(),
            com.niuqu.chatbubble.ChatBubbleMod.mediaStore(player.getServer()));
    }
}
