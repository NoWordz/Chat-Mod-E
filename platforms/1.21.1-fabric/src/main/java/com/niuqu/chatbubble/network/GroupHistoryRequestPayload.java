package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * C2S request for one bounded page of durable group history (2.5.0 v2). Only a
 * client that completed the handshake and is a member of {@code groupId} is
 * answered with rows; the server clamps both {@code after} and {@code limit} and
 * never lets the caller advance past its own membership lower bound.
 */
public record GroupHistoryRequestPayload(UUID groupId, long after, int limit) implements CustomPayload {

    /** Largest page a client may ask for (matches the shared store's page bound). */
    public static final int MAX_PAGE_LIMIT = 100;

    public static final CustomPayload.Id<GroupHistoryRequestPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_history_request_v2"));

    public static final PacketCodec<PacketByteBuf, GroupHistoryRequestPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeUuid(value.groupId);
            buf.writeLong(value.after);
            buf.writeVarInt(value.limit);
        },
        buf -> new GroupHistoryRequestPayload(buf.readUuid(), buf.readLong(), buf.readVarInt())
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Client-side dispatch (callers must have checked {@code canSend} first). */
    public static void send(UUID groupId, long after, int limit) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            new GroupHistoryRequestPayload(groupId, after, limit));
    }

    /** Server-side hook, invoked from ChatBubbleMod's receiver. */
    public static void handleServer(GroupHistoryRequestPayload payload, ServerPlayerEntity player) {
        com.niuqu.chatbubble.server.GroupManager.handleHistoryRequest(
            player, payload.groupId(), payload.after(), payload.limit());
    }
}
