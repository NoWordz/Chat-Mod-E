package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * C2S group lifecycle action (2.5.0 v2), id {@code e33chat:group_action_v2} of the
 * optional {@code e33chat:groups} protocol.
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
public record GroupActionV2Payload(int action, UUID groupId, UUID subjectId, UUID invitationId, String value)
        implements CustomPayload {

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

    public static final CustomPayload.Id<GroupActionV2Payload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_action_v2"));

    public static final PacketCodec<PacketByteBuf, GroupActionV2Payload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeVarInt(value.action);
            buf.writeUuid(value.groupId());
            buf.writeUuid(value.subjectId());
            buf.writeUuid(value.invitationId());
            buf.writeString(value.value(), MAX_VALUE);
        },
        buf -> new GroupActionV2Payload(buf.readVarInt(), buf.readUuid(), buf.readUuid(), buf.readUuid(),
            buf.readString(MAX_VALUE))
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Client-side dispatch; a no-op when this server did not declare the groups channel. */
    public static void send(int action, UUID groupId, UUID subjectId, UUID invitationId, String value) {
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(ID)) return;
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new GroupActionV2Payload(
            action,
            groupId == null ? NONE : groupId,
            subjectId == null ? NONE : subjectId,
            invitationId == null ? NONE : invitationId,
            value == null ? NO_VALUE : value));
    }

    /** Server-side hook, invoked from ChatBubbleMod's receiver. */
    public static void handleServer(GroupActionV2Payload payload, ServerPlayerEntity player) {
        com.niuqu.chatbubble.server.GroupManager.handleActionV2(player, payload.action(), payload.groupId(),
            payload.subjectId(), payload.invitationId(), payload.value());
    }
}
