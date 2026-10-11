package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.server.GroupManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S v2 group handshake. Sent right after login in addition to (never instead
 * of) the v1 {@link ClientHelloPayload}: a server without the optional payloads
 * never answers, so the connection keeps the 2.4.x group behaviour.
 *
 * <p>The server answers with a {@link GroupProtocolAckPayload} and, only for an
 * accepted protocol, the recipient-scoped directory.
 */
public record GroupProtocolHelloPayload(int protocol) implements CustomPacketPayload {

    /** v2 group protocol this build speaks; the server ACKs only a matching offer. */
    public static final int GROUP_PROTOCOL = 1;

    public static final Type<GroupProtocolHelloPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "group_protocol_hello_v2"));

    public static final StreamCodec<ByteBuf, GroupProtocolHelloPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GroupProtocolHelloPayload decode(ByteBuf buf) {
            return new GroupProtocolHelloPayload(new FriendlyByteBuf(buf).readVarInt());
        }

        @Override
        public void encode(ByteBuf buf, GroupProtocolHelloPayload payload) {
            new FriendlyByteBuf(buf).writeVarInt(payload.protocol());
        }
    };

    @Override
    public Type<GroupProtocolHelloPayload> type() { return TYPE; }

    public static void handleServer(GroupProtocolHelloPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player != null) {
                GroupManager.onGroupProtocolHello(player, payload.protocol());
            }
        });
    }

    /** Client-side send (the login hook checks that the peer declared it first). */
    public static void send() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new GroupProtocolHelloPayload(GROUP_PROTOCOL));
    }
}
