package com.niuqu.chatbubble.packets;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -&gt; server: one chunk of a GROUP media upload (2.5.0 M9).
 *
 * <p>Separate from {@link MediaUploadPayload} on purpose: this path always means
 * "store this as group-owned media". The handler forces group mode, so a chunk
 * whose uploadId has no live, authorized {@link GroupMediaContextPayload} is
 * rejected outright — a refused, expired or missing context can never fall
 * through to an ordinary public save.
 *
 * <p>Same six wire fields as the public upload chunk.
 */
public record GroupMediaUploadPayload(long uploadId, int index, int totalChunks,
                                      int totalBytes, String contentType, byte[] chunk)
        implements CustomPacketPayload {

    public static final Type<GroupMediaUploadPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "group_media_upload_v2"));

    public static final StreamCodec<ByteBuf, GroupMediaUploadPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GroupMediaUploadPayload decode(ByteBuf buf) {
            return new GroupMediaUploadPayload(
                buf.readLong(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                MediaUploadPayload.readUtf(buf),
                MediaUploadPayload.readByteArray(buf)
            );
        }

        @Override
        public void encode(ByteBuf buf, GroupMediaUploadPayload payload) {
            buf.writeLong(payload.uploadId());
            buf.writeInt(payload.index());
            buf.writeInt(payload.totalChunks());
            buf.writeInt(payload.totalBytes());
            MediaUploadPayload.writeUtf(buf, payload.contentType() != null ? payload.contentType() : "");
            buf.writeInt(payload.chunk().length);
            buf.writeBytes(payload.chunk());
        }
    };

    @Override
    public Type<GroupMediaUploadPayload> type() { return TYPE; }

    public static void handleServer(GroupMediaUploadPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sender) {
                com.niuqu.chatbubble.server.MediaService.handleGroupUpload(sender,
                    com.niuqu.chatbubble.server.ChatServerListener.mediaStore(),
                    payload.uploadId(), payload.index(), payload.totalChunks(), payload.totalBytes(),
                    payload.contentType(), payload.chunk());
            }
        });
    }
}
