package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.network.NetworkHandler;
import com.niuqu.chatbubble.server.GroupManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S group lifecycle action (2.5.0 v2) on the {@code e33chat:groups} channel.
 *
 * <p>The wire shape is exactly {@code (int action, UUID groupId, UUID subjectId,
 * UUID invitationId, String value)}; unused UUID fields are the all-zero UUID and an
 * unused value is the empty string. The actor is never on the wire: the server uses
 * the connection that delivered the payload.
 *
 * <p>Actions: 0 create (value = name), 1 invite (group, subject = invitee; the
 * invitee's display name in value is accepted too and resolved server-side),
 * 2 accept (invitation), 3 reject (invitation), 4 revoke (invitation), 5 leave (group),
 * 6 rename (group, value = new name), 7 kick (group, subject), 8 transfer ownership
 * (group, subject), 9 disband (group).
 */
public record GroupActionV2Packet(int action, UUID groupId, UUID subjectId, UUID invitationId, String value) {

    public static final int CREATE = 0;
    public static final int INVITE = 1;
    public static final int ACCEPT = 2;
    public static final int REJECT = 3;
    public static final int REVOKE = 4;
    public static final int LEAVE = 5;
    public static final int RENAME = 6;
    public static final int KICK = 7;
    public static final int TRANSFER_OWNER = 8;
    public static final int DISBAND = 9;

    /** All-zero UUID: the placeholder for a field this action does not use. */
    public static final UUID NONE = new UUID(0L, 0L);
    /** The placeholder for a text field this action does not use. */
    public static final String NO_VALUE = "";

    // A group name is at most 12 characters server-side; the wire bound only stops
    // a hostile client from sending an oversized frame.
    private static final int MAX_VALUE = 64;

    public static void encode(GroupActionV2Packet packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.action);
        buf.writeUUID(packet.groupId);
        buf.writeUUID(packet.subjectId);
        buf.writeUUID(packet.invitationId);
        buf.writeUtf(packet.value, MAX_VALUE);
    }

    public static GroupActionV2Packet decode(FriendlyByteBuf buf) {
        return new GroupActionV2Packet(buf.readVarInt(), buf.readUUID(), buf.readUUID(), buf.readUUID(),
            buf.readUtf(MAX_VALUE));
    }

    /** Client-side dispatch; a no-op when this server did not declare the groups channel. */
    public static void send(int action, UUID groupId, UUID subjectId, UUID invitationId, String value) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        var connection = mc.player.connection.getConnection();
        if (connection == null || !NetworkHandler.GROUPS_CHANNEL.isRemotePresent(connection)) return;
        NetworkHandler.GROUPS_CHANNEL.sendToServer(new GroupActionV2Packet(action,
            groupId == null ? NONE : groupId,
            subjectId == null ? NONE : subjectId,
            invitationId == null ? NONE : invitationId,
            value == null ? NO_VALUE : value));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) handleServer(this, player);
        });
        ctx.get().setPacketHandled(true);
    }

    /** Server-side hook, invoked from the packet receiver. */
    public static void handleServer(GroupActionV2Packet payload, ServerPlayer player) {
        GroupManager.handleActionV2(player, payload.action, payload.groupId, payload.subjectId,
            payload.invitationId, payload.value);
    }
}
