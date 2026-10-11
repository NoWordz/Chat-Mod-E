package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.ChatBubbleMod;
import com.niuqu.chatbubble.server.MediaService;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

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
        implements CustomPayload {

    public static final CustomPayload.Id<GroupMediaUploadPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_media_upload_v2"));

    public static final PacketCodec<PacketByteBuf, GroupMediaUploadPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeLong(value.uploadId);
            buf.writeInt(value.index);
            buf.writeInt(value.totalChunks);
            buf.writeInt(value.totalBytes);
            buf.writeString(value.contentType != null ? value.contentType : "");
            buf.writeByteArray(value.chunk);
        },
        buf -> new GroupMediaUploadPayload(
            buf.readLong(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            // Read bounds aligned with the public chunk payload: a hostile client
            // must not be able to push oversized strings or chunks past the
            // decoder (out-of-range throws, so the packet is dropped whole).
            buf.readString(128),
            buf.readByteArray(com.niuqu.chatbubble.server.DiskMediaStore.CHUNK_BYTES)
        )
    );

    @Override
    public Id<GroupMediaUploadPayload> getId() { return ID; }

    public static void handleServer(GroupMediaUploadPayload payload, ServerPlayerEntity player,
                                    boolean mediaEnabled, boolean autoClean) {
        MediaService.handleGroupUpload(player, ChatBubbleMod.mediaStore(player.getServer()),
            mediaEnabled, autoClean, payload.uploadId(), payload.index(), payload.totalChunks(),
            payload.totalBytes(), payload.contentType(), payload.chunk());
    }
}
