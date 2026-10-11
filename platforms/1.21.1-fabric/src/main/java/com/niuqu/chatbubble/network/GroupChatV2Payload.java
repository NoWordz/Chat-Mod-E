package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.group.GroupHistoryEntry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * S2C live group message (2.5.0 v2). Members that completed the hello/ACK
 * handshake receive this instead of the legacy {@link GroupChatPayload}, so the
 * client can store the durable {@code (groupId, sequence)} identity. Non-negotiated
 * mod clients keep receiving the v1 payload.
 *
 * <p>The display name is deliberately not on the wire: it comes from the private
 * directory the client already holds for this group id, so a renamed group and a
 * same-name different group id can never be confused.
 */
public record GroupChatV2Payload(UUID groupId, long sequence, UUID senderUUID, String senderName,
                                 String content, String quoteSender,
                                 String quoteContent) implements CustomPayload {

    // Server caps content; the codec below caps reads via readString(max)
    private static final int MAX_TEXT = 2048;
    private static final int MAX_NAME = 256;

    public static final CustomPayload.Id<GroupChatV2Payload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_chat_v2"));

    public static final PacketCodec<PacketByteBuf, GroupChatV2Payload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeUuid(value.groupId);
            buf.writeLong(value.sequence);
            buf.writeUuid(value.senderUUID);
            buf.writeString(value.senderName, MAX_NAME);
            buf.writeString(value.content, MAX_TEXT);
            buf.writeString(value.quoteSender != null ? value.quoteSender : "", MAX_NAME);
            buf.writeString(value.quoteContent != null ? value.quoteContent : "", MAX_TEXT);
        },
        buf -> new GroupChatV2Payload(
            buf.readUuid(),
            buf.readLong(),
            buf.readUuid(),
            buf.readString(MAX_NAME),
            buf.readString(MAX_TEXT),
            blankToNull(buf.readString(MAX_NAME)),
            blankToNull(buf.readString(MAX_TEXT))
        )
    );

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Builds the live row for one durable entry; the entry owns id and sequence. */
    public static GroupChatV2Payload of(GroupHistoryEntry entry) {
        return new GroupChatV2Payload(entry.groupId(), entry.sequence(), entry.senderUUID(),
            entry.senderName(), entry.content(), entry.replySender(), entry.replyContent());
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Client-side hook, invoked from ChatBubbleClientSetup's receiver. */
    public static void handleClient(GroupChatV2Payload payload) {
        // TODO(M5): the client group state machine authorizes this live row against
        // the directory it holds (visibleFromSequence) and hands it to the store; the
        // v2 client pipeline lands in M5/M6. The wire shape is frozen here.
    }
}
