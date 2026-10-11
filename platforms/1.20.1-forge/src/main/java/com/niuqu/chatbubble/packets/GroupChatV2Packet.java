package com.niuqu.chatbubble.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C live group message (v2), sent only to members that ACKed the handshake.
 * The v1 {@link GroupChatPacket} keeps serving connections that did not, so the
 * 2.4.x group chat does not break during the transition.
 *
 * <p>The wire carries the stable group id and the server sequence; the display
 * name comes from the directory the client already holds, so a rename can never
 * relabel an in-flight message.
 */
public class GroupChatV2Packet {

    // Server caps content; still guard the decode side against hostile servers
    private static final int MAX_TEXT = 2048;
    private static final int MAX_NAME = 256;

    private final UUID groupId;
    private final long sequence;
    private final UUID senderUUID;
    private final String senderName;
    private final String content;
    private final String quoteSender;
    private final String quoteContent;

    public GroupChatV2Packet(UUID groupId, long sequence, UUID senderUUID, String senderName,
                             String content, String quoteSender, String quoteContent) {
        this.groupId = groupId;
        this.sequence = sequence;
        this.senderUUID = senderUUID;
        this.senderName = senderName;
        this.content = content;
        this.quoteSender = quoteSender;
        this.quoteContent = quoteContent;
    }

    public static void encode(GroupChatV2Packet packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.groupId != null ? packet.groupId : new UUID(0, 0));
        buf.writeLong(packet.sequence);
        buf.writeUUID(packet.senderUUID != null ? packet.senderUUID : new UUID(0, 0));
        buf.writeUtf(packet.senderName != null ? packet.senderName : "", MAX_NAME);
        buf.writeUtf(packet.content != null ? packet.content : "", MAX_TEXT);
        buf.writeUtf(packet.quoteSender != null ? packet.quoteSender : "", MAX_NAME);
        buf.writeUtf(packet.quoteContent != null ? packet.quoteContent : "", MAX_TEXT);
    }

    public static GroupChatV2Packet decode(FriendlyByteBuf buf) {
        return new GroupChatV2Packet(
            buf.readUUID(),
            buf.readLong(),
            buf.readUUID(),
            buf.readUtf(MAX_NAME),
            buf.readUtf(MAX_TEXT),
            blankToNull(buf.readUtf(MAX_NAME)),
            blankToNull(buf.readUtf(MAX_TEXT)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // TODO(M5): the client group state machine authorizes this live row against
        // the directory it holds (visibleFromSequence) and hands it to the store; the
        // v2 client pipeline lands in M5/M6. The wire shape is frozen here.
        ctx.get().setPacketHandled(true);
    }
}
