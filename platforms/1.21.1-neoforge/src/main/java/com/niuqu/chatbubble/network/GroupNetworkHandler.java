package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import com.niuqu.chatbubble.packets.GroupChatV2Payload;
import com.niuqu.chatbubble.packets.GroupDirectoryChunkPayload;
import com.niuqu.chatbubble.packets.GroupDirectoryPayload;
import com.niuqu.chatbubble.packets.GroupHistoryPagePayload;
import com.niuqu.chatbubble.packets.GroupProtocolAckPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Server-side senders for the v2 group channel (2.5.0). Every payload goes only
 * to a client that declared it: the registrations are optional, so sending an
 * unnegotiated payload would abort the send with an exception. Everything else
 * keeps the v1 main-channel path.
 */
public final class GroupNetworkHandler {

    private GroupNetworkHandler() {}

    public static void sendAck(ServerPlayer player, int protocol, boolean accepted) {
        send(player, new GroupProtocolAckPayload(protocol, accepted));
    }

    /**
     * The recipient-scoped directory: the groups that player is in plus their live
     * invitations. A directory that fits one packet travels as the ordinary
     * {@link GroupDirectoryPayload}; anything larger is split into
     * {@link GroupDirectoryChunkPayload} slices that the client reassembles into
     * one complete snapshot before it applies any of it.
     */
    public static void sendDirectory(ServerPlayer player, List<GroupDirectoryEntry> groups,
                                     List<GroupInvitationSummary> invitations) {
        if (player == null) return;
        GroupDirectoryPayload directory = new GroupDirectoryPayload(groups, invitations);
        if (!player.connection.hasChannel(directory.type())) return;
        byte[] encoded = encode(directory);
        if (encoded.length <= GroupDirectoryChunkPayload.MAX_CHUNK_BYTES) {
            send(player, directory);
            return;
        }
        List<GroupDirectoryChunkPayload> chunks =
            GroupDirectoryChunkPayload.split(UUID.randomUUID(), encoded);
        if (chunks == null) {
            // Beyond the transfer budget — unreachable with the pinned configuration
            // ranges. Fail loudly instead of handing the client a partial directory,
            // and tell the recipient (without naming any group) that the sync did not
            // happen rather than letting the UI keep a stale snapshot.
            com.mojang.logging.LogUtils.getLogger().error(
                "[e33chat] Group directory for {} is {} bytes, beyond the chunk budget; not sent",
                player.getName().getString(), encoded.length);
            player.sendSystemMessage(
                net.minecraft.network.chat.Component.translatable("e33chat.group.directory_failed"));
            return;
        }
        for (GroupDirectoryChunkPayload chunk : chunks) send(player, chunk);
    }

    public static void sendChat(ServerPlayer player, GroupHistoryEntry entry) {
        send(player, new GroupChatV2Payload(entry.groupId(), entry.sequence(), entry.senderUUID(),
            entry.senderName(), entry.content(), entry.replySender(), entry.replyContent()));
    }

    public static void sendHistoryPage(ServerPlayer player, GroupHistoryPage page) {
        send(player, new GroupHistoryPagePayload(page));
    }

    /** The exact wire bytes of one directory payload, reusing that payload's own codec. */
    private static byte[] encode(GroupDirectoryPayload directory) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            GroupDirectoryPayload.STREAM_CODEC.encode(buf, directory);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static <T extends CustomPacketPayload> void send(ServerPlayer player, T payload) {
        if (player == null) return;
        // Optional registration: a 2.4.x / vanilla client never negotiated it, and
        // that connection keeps the v1 directory and chat instead.
        if (!player.connection.hasChannel(payload.type())) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
    }
}
