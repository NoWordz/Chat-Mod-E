package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.chat.GroupChannelState;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C reply to {@link GroupProtocolHelloPayload}. {@code protocol} echoes the
 * client's offer and {@code accepted} tells whether the server speaks it.
 *
 * <p>Only an accepted {@link GroupChannelState#GROUP_PROTOCOL} unlocks the v2
 * paths: an ACK for another version must not make the client consume a directory
 * it cannot interpret.
 */
public record GroupProtocolAckPayload(int protocol, boolean accepted) implements CustomPacketPayload {

    public static final Type<GroupProtocolAckPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "group_protocol_ack_v2"));

    public static final StreamCodec<ByteBuf, GroupProtocolAckPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GroupProtocolAckPayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            return new GroupProtocolAckPayload(friendly.readVarInt(), friendly.readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, GroupProtocolAckPayload payload) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeVarInt(payload.protocol());
            friendly.writeBoolean(payload.accepted());
        }
    };

    @Override
    public Type<GroupProtocolAckPayload> type() { return TYPE; }

    public static void handleClient(GroupProtocolAckPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> GroupChannelState.onAck(payload.protocol(), payload.accepted()));
    }
}
