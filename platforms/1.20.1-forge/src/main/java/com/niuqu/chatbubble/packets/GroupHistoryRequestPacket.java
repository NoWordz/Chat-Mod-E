package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.network.NetworkHandler;
import com.niuqu.chatbubble.server.GroupManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S authorized history paging request: "rows of this group after this
 * sequence, at most this many". The server decides what the recipient may see
 * (membership + join window), so the client can never widen its own scope.
 */
public class GroupHistoryRequestPacket {

    /** Must match the server's page bound and GroupChannelState.PAGE_LIMIT. */
    public static final int MAX_LIMIT = 100;

    private final UUID groupId;
    private final long after;
    private final int limit;

    public GroupHistoryRequestPacket(UUID groupId, long after, int limit) {
        this.groupId = groupId;
        this.after = after;
        this.limit = limit;
    }

    public UUID groupId() {
        return groupId;
    }

    public long after() {
        return after;
    }

    public int limit() {
        return limit;
    }

    public static void encode(GroupHistoryRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.groupId != null ? packet.groupId : new UUID(0, 0));
        buf.writeLong(packet.after);
        buf.writeVarInt(packet.limit);
    }

    public static GroupHistoryRequestPacket decode(FriendlyByteBuf buf) {
        UUID groupId = buf.readUUID();
        // Defensive bounds; the server clamps identically, so a hostile or buggy
        // client cannot turn this into an unbounded read.
        long after = Math.max(buf.readLong(), 0L);
        int limit = Math.max(Math.min(buf.readVarInt(), MAX_LIMIT), 0);
        return new GroupHistoryRequestPacket(groupId, after, limit);
    }

    /** Client-side send, from the directory/page handlers and the login hook. */
    public static void send(UUID groupId, long after, int limit) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        var connection = mc.player.connection.getConnection();
        if (connection == null || !NetworkHandler.GROUPS_CHANNEL.isRemotePresent(connection)) return;
        NetworkHandler.GROUPS_CHANNEL.sendToServer(new GroupHistoryRequestPacket(groupId, after, limit));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                GroupManager.handleHistoryRequest(player, groupId, after, limit);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
