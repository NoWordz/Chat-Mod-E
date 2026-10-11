package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C handshake answer (2.5.0). {@code accepted=true} means the server speaks the
 * requested group protocol and will follow up with the recipient-scoped v2
 * directory; {@code accepted=false} leaves the client on the legacy v1 flow.
 */
public record GroupProtocolAckPayload(int protocolVersion, boolean accepted) implements CustomPayload {

    public static final CustomPayload.Id<GroupProtocolAckPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_protocol_ack_v2"));

    public static final PacketCodec<PacketByteBuf, GroupProtocolAckPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeVarInt(value.protocolVersion);
            buf.writeBoolean(value.accepted);
        },
        buf -> new GroupProtocolAckPayload(buf.readVarInt(), buf.readBoolean())
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Client-side hook, invoked from ChatBubbleClientSetup's receiver. */
    public static void handleClient(GroupProtocolAckPayload payload) {
        // TODO(M5): the client group state machine (GroupChannelState.onAck in the
        // M6-owned chat package) consumes this ACK; the v2 client UI lands in M5.
    }
}
