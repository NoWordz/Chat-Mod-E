package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import com.niuqu.chatbubble.packets.GroupChatV2Packet;
import com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket;
import com.niuqu.chatbubble.packets.GroupDirectoryPacket;
import com.niuqu.chatbubble.packets.GroupHistoryPagePacket;
import com.niuqu.chatbubble.packets.GroupProtocolAckPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side senders for the v2 group channel (2.5.0). Every payload goes only
 * to a connection that proved it carries {@code e33chat:groups} by sending the
 * handshake; everything else keeps the v1 main-channel path.
 */
public final class GroupNetworkHandler {

    /** Player -> the connection the handshake arrived on. Populated by the hello
     *  handler; a stale entry (logged out, replaced) is pruned on use, and the
     *  lifecycle hooks below drop it eagerly so a closed connection is never
     *  retained until the JVM exits (a singleplayer world switch keeps the JVM
     *  alive). Package-visible so the lifecycle test asserts the real map, not a
     *  copy or a source-text check. */
    static final Map<UUID, Connection> v2Connections = new HashMap<>();

    private GroupNetworkHandler() {}

    /** Remember that this player's connection carries the groups channel. */
    public static void trackV2Connection(UUID playerId, Connection connection) {
        if (playerId == null || connection == null) return;
        v2Connections.put(playerId, connection);
    }

    /** Drop the connection retained for a player that is leaving; the closed
     *  {@link Connection} must not stay reachable through this static map. */
    public static void forgetV2Connection(UUID playerId) {
        if (playerId == null) return;
        v2Connections.remove(playerId);
    }

    /** Drop every retained connection. Called when the server stops: a client-side
     *  world switch reuses the JVM, so nothing else would ever release them. */
    public static void clearV2Connections() {
        v2Connections.clear();
    }

    /** Whether that connection declared it can receive the groups channel. Used by
     *  the group backend to pick the refusal wording and to skip the v1 fallback
     *  for a peer that only has a stale UI. */
    public static boolean isV2Connection(ServerPlayer player) {
        return player != null && canSend(player);
    }

    public static void sendAck(ServerPlayer player, int protocol, boolean accepted) {
        send(player, new GroupProtocolAckPacket(protocol, accepted));
    }

    /**
     * The recipient-scoped directory: the groups that player is in plus their live
     * invitations. A directory that fits one packet travels as the ordinary
     * {@link GroupDirectoryPacket}; anything larger is split into
     * {@link GroupDirectoryChunkPacket} slices that the client reassembles into
     * one complete snapshot before it applies any of it.
     */
    public static void sendDirectory(ServerPlayer player, List<GroupDirectoryEntry> groups,
                                     List<GroupInvitationSummary> invitations) {
        if (player == null) return;
        GroupDirectoryPacket directory = new GroupDirectoryPacket(groups, invitations);
        if (!canSend(player)) return;
        byte[] encoded = encode(directory);
        if (encoded.length <= GroupDirectoryChunkPacket.MAX_CHUNK_BYTES) {
            send(player, directory);
            return;
        }
        List<GroupDirectoryChunkPacket> chunks = GroupDirectoryChunkPacket.split(UUID.randomUUID(), encoded);
        if (chunks == null) {
            // Beyond the transfer budget — unreachable with the pinned configuration
            // ranges. Fail loudly instead of handing the client a partial directory,
            // and tell the authorized recipient (no group name, so no membership leak).
            com.mojang.logging.LogUtils.getLogger().error(
                "[e33chat] Group directory for {} is {} bytes, beyond the chunk budget; not sent",
                player.getName().getString(), encoded.length);
            player.sendSystemMessage(Component.translatable("e33chat.group.directory_failed"));
            return;
        }
        sendFragments(player, chunks);
    }

    public static void sendChat(ServerPlayer player, GroupHistoryEntry entry) {
        send(player, new GroupChatV2Packet(entry.groupId(), entry.sequence(), entry.senderUUID(),
            entry.senderName(), entry.content(), entry.replySender(), entry.replyContent()));
    }

    public static void sendHistoryPage(ServerPlayer player, GroupHistoryPage page) {
        send(player, new GroupHistoryPagePacket(page));
    }

    /** Sends every slice of one directory to the connection that negotiated it. */
    private static void sendFragments(ServerPlayer player, List<GroupDirectoryChunkPacket> chunks) {
        for (GroupDirectoryChunkPacket chunk : chunks) send(player, chunk);
    }

    /** The exact wire bytes of one directory payload, reusing that payload's own codec. */
    private static byte[] encode(GroupDirectoryPacket directory) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            GroupDirectoryPacket.encode(directory, buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /** Whether this player's retained connection is live and carries the groups channel. */
    private static boolean canSend(ServerPlayer player) {
        Connection connection = v2Connections.get(player.getUUID());
        if (connection == null || !connection.isConnected()) {
            if (connection != null) v2Connections.remove(player.getUUID());
            return false;   // never negotiated the groups channel (or already gone)
        }
        return NetworkHandler.GROUPS_CHANNEL.isRemotePresent(connection);
    }

    private static <MSG> void send(ServerPlayer player, MSG message) {
        if (player == null || !canSend(player)) return;
        NetworkHandler.GROUPS_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
