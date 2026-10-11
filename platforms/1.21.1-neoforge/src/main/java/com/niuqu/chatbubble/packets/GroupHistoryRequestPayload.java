package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.server.GroupManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S authorized history paging request: "rows of this group after this
 * sequence, at most this many". The server decides what the recipient may see
 * (membership + join window), so the client can never widen its own scope.
 */
public record GroupHistoryRequestPayload(UUID groupId, long after, int limit) implements CustomPacketPayload {

    /** Must match the server's page bound and GroupChannelState.PAGE_LIMIT. */
    public static final int MAX_LIMIT = 100;

    public static final Type<GroupHistoryRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "group_history_request_v2"));

    public static final StreamCodec<ByteBuf, GroupHistoryRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GroupHistoryRequestPayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            UUID groupId = friendly.readUUID();
            // Defensive bounds; the server clamps identically, so a hostile or
            // buggy client cannot turn this into an unbounded read.
            long after = Math.max(friendly.readLong(), 0L);
            int limit = Math.max(Math.min(friendly.readVarInt(), MAX_LIMIT), 0);
            return new GroupHistoryRequestPayload(groupId, after, limit);
        }

        @Override
        public void encode(ByteBuf buf, GroupHistoryRequestPayload payload) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeUUID(payload.groupId() != null ? payload.groupId() : new UUID(0, 0));
            friendly.writeLong(payload.after());
            friendly.writeVarInt(payload.limit());
        }
    };

    @Override
    public Type<GroupHistoryRequestPayload> type() { return TYPE; }

    /** Client-side send, from the directory/page handlers. Guarded: a serverbound
     *  payload the peer never declared would abort the send with an exception. */
    public static void send(UUID groupId, long after, int limit) {
        var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (listener == null || !listener.hasChannel(TYPE.id())) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new GroupHistoryRequestPayload(groupId, after, limit));
    }

    public static void handleServer(GroupHistoryRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player != null) {
                GroupManager.handleHistoryRequest(player, payload.groupId(), payload.after(), payload.limit());
            }
        });
    }
}
