package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * C2S group-protocol handshake (2.5.0). Sent by a client that runs E33Chat 2.5+
 * right after login, only when the server declared this channel — a 2.4.x server
 * never does, so an old server simply keeps the legacy v1 group flow.
 *
 * The server answers with {@link GroupProtocolAckPayload}; only after an accepted
 * ACK does the client accept the recipient-scoped v2 directory.
 */
public record GroupProtocolHelloPayload(int protocolVersion) implements CustomPayload {

    /** Current group protocol version. Version 1 is the only one this build speaks. */
    public static final int PROTOCOL_VERSION = 1;

    public static final CustomPayload.Id<GroupProtocolHelloPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_protocol_hello_v2"));

    public static final PacketCodec<PacketByteBuf, GroupProtocolHelloPayload> CODEC = PacketCodec.of(
        (value, buf) -> buf.writeVarInt(value.protocolVersion),
        buf -> new GroupProtocolHelloPayload(buf.readVarInt())
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Client-side dispatch (callers must have checked {@code canSend} first). */
    public static void send() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            new GroupProtocolHelloPayload(PROTOCOL_VERSION));
    }

    /** Server-side hook, invoked from ChatBubbleMod's receiver. */
    public static void handleServer(GroupProtocolHelloPayload payload, ServerPlayerEntity player) {
        com.niuqu.chatbubble.server.GroupManager.onGroupProtocolHello(player, payload.protocolVersion());
    }
}
