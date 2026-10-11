package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.server.MediaService;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
public record GroupMediaContextPayload(UUID groupId, long uploadId) implements CustomPacketPayload {

    public static final Type<GroupMediaContextPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "group_media_context_v2"));

    public static final StreamCodec<ByteBuf, GroupMediaContextPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GroupMediaContextPayload decode(ByteBuf buf) {
            return new GroupMediaContextPayload(readUuid(buf), buf.readLong());
        }

        @Override
        public void encode(ByteBuf buf, GroupMediaContextPayload payload) {
            writeUuid(buf, payload.groupId() != null ? payload.groupId() : new UUID(0, 0));
            buf.writeLong(payload.uploadId());
        }
    };

    // Raw ByteBuf lacks the uuid helpers — route through FriendlyByteBuf
    private static void writeUuid(ByteBuf buf, UUID id) {
        new FriendlyByteBuf(buf).writeUUID(id);
    }

    private static UUID readUuid(ByteBuf buf) {
        return new FriendlyByteBuf(buf).readUUID();
    }

    @Override
    public Type<GroupMediaContextPayload> type() { return TYPE; }

    public static void handleServer(GroupMediaContextPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sender) {
                MediaService.prepareGroupUploadContext(sender, payload.groupId(), payload.uploadId());
            }
        });
    }
}
