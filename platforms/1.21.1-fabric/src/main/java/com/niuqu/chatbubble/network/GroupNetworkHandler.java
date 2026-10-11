package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.UUID;

/**
 * Server-side senders for the v2 group channel (2.5.0). Every payload goes only to
 * a client that declared it: a 2.4.x or vanilla connection keeps the v1 main-channel
 * path, and sending an unnegotiated payload would be dropped (or, on a loader that
 * validates registration, throw), so each send is gated on the real channel set.
 */
public final class GroupNetworkHandler {

    private GroupNetworkHandler() {}

    public static void sendAck(ServerPlayerEntity player, int protocol, boolean accepted) {
        send(player, new GroupProtocolAckPayload(protocol, accepted));
    }

    /**
     * The recipient-scoped directory: the groups that player is in plus their live
     * invitations. A directory that fits one packet travels as the ordinary
     * {@link GroupDirectoryPayload}; anything larger is split into
     * {@link GroupDirectoryChunkPayload} slices that the client reassembles into
     * one complete snapshot before it applies any of it.
     */
    public static void sendDirectory(ServerPlayerEntity player, List<GroupDirectoryEntry> groups,
                                     List<GroupInvitationSummary> invitations) {
        if (player == null) return;
        GroupDirectoryPayload directory = new GroupDirectoryPayload(groups, invitations);
        if (!ServerPlayNetworking.canSend(player, directory.getId())) return;
        byte[] encoded = encode(directory);
        if (encoded.length <= GroupDirectoryChunkPayload.MAX_CHUNK_BYTES) {
            ServerPlayNetworking.send(player, directory);
            return;
        }
        List<GroupDirectoryChunkPayload> chunks =
            GroupDirectoryChunkPayload.split(UUID.randomUUID(), encoded);
        if (chunks == null) {
            // Beyond the transfer budget — unreachable with the pinned configuration
            // ranges. Fail loudly instead of handing the client a partial directory,
            // and tell the authorized recipient that its directory did not arrive
            // (without naming any group it is not a member of).
            com.mojang.logging.LogUtils.getLogger().error(
                "[e33chat] Group directory for {} is {} bytes, beyond the chunk budget; not sent",
                player.getName().getString(), encoded.length);
            player.sendMessage(net.minecraft.text.Text.translatable("e33chat.group.directory_failed"), false);
            return;
        }
        for (GroupDirectoryChunkPayload chunk : chunks) send(player, chunk);
    }

    public static void sendChat(ServerPlayerEntity player, GroupHistoryEntry entry) {
        send(player, GroupChatV2Payload.of(entry));
    }

    public static void sendHistoryPage(ServerPlayerEntity player, GroupHistoryPage page) {
        send(player, new GroupHistoryPagePayload(page));
    }

    /** The exact wire bytes of one directory payload, reusing that payload's own codec. */
    private static byte[] encode(GroupDirectoryPayload directory) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            GroupDirectoryPayload.CODEC.encode(buf, directory);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static <T extends CustomPayload> void send(ServerPlayerEntity player, T payload) {
        if (player == null) return;
        if (!ServerPlayNetworking.canSend(player, payload.getId())) return;
        ServerPlayNetworking.send(player, payload);
    }
}
