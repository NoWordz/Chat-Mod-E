package com.niuqu.chatbubble.packets;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * S2C live group message (v2), sent only to members that ACKed the handshake.
 * The v1 {@link GroupChatPayload} keeps serving connections that did not, so the
 * 2.4.x group chat does not break during the transition.
 *
 * <p>The wire carries the stable group id and the server sequence; the display
 * name comes from the directory the client already holds, so a rename can never
 * relabel an in-flight message.
 */
public record GroupChatV2Payload(UUID groupId, long sequence, UUID senderUUID, String senderName,
                                 String content, String quoteSender, String quoteContent)
        implements CustomPacketPayload {

    // Server caps content; still guard the decode side against hostile servers
    private static final int MAX_TEXT = 2048;
    private static final int MAX_NAME = 256;

    public static final Type<GroupChatV2Payload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "group_chat_v2"));

    public static final StreamCodec<ByteBuf, GroupChatV2Payload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GroupChatV2Payload decode(ByteBuf buf) {
            return new GroupChatV2Payload(
                readUuid(buf),
                buf.readLong(),
                readUuid(buf),
                readString(buf, MAX_NAME),
                readString(buf, MAX_TEXT),
                blankToNull(readString(buf, MAX_NAME)),
                blankToNull(readString(buf, MAX_TEXT)));
        }

        @Override
        public void encode(ByteBuf buf, GroupChatV2Payload payload) {
            writeUuid(buf, payload.groupId() != null ? payload.groupId() : new UUID(0, 0));
            buf.writeLong(payload.sequence());
            writeUuid(buf, payload.senderUUID() != null ? payload.senderUUID() : new UUID(0, 0));
            writeString(buf, payload.senderName() != null ? payload.senderName() : "", MAX_NAME);
            writeString(buf, payload.content() != null ? payload.content() : "", MAX_TEXT);
            writeString(buf, payload.quoteSender() != null ? payload.quoteSender() : "", MAX_NAME);
            writeString(buf, payload.quoteContent() != null ? payload.quoteContent() : "", MAX_TEXT);
        }
    };

    // Raw ByteBuf lacks the varint/utf/uuid helpers — route through FriendlyByteBuf
    private static void writeUuid(ByteBuf buf, UUID id) {
        new FriendlyByteBuf(buf).writeUUID(id);
    }

    private static UUID readUuid(ByteBuf buf) {
        return new FriendlyByteBuf(buf).readUUID();
    }

    private static void writeString(ByteBuf buf, String s, int cap) {
        new FriendlyByteBuf(buf).writeUtf(s, cap);
    }

    private static String readString(ByteBuf buf, int cap) {
        return new FriendlyByteBuf(buf).readUtf(cap);
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    @Override
    public Type<GroupChatV2Payload> type() { return TYPE; }

    public static void handleClient(GroupChatV2Payload payload, IPayloadContext context) {
        // TODO(M5): the client group state machine authorizes this live row against
        // the directory it holds (visibleFromSequence) and hands it to the store; the
        // v2 client pipeline lands in M5/M6. The wire shape is frozen here.
    }
}
