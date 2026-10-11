package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.server.ChatServerListener;
import com.niuqu.chatbubble.server.DiskMediaStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -&gt; server: one chunk of a GROUP media upload (2.5.0 M9).
 *
 * <p>Separate from {@link MediaUploadPacket} on purpose: this path always means
 * "store this as group-owned media". The handler forces group mode, so a chunk
 * whose uploadId has no live, authorized {@link GroupMediaContextPacket} is
 * rejected outright — a refused, expired or missing context can never fall
 * through to an ordinary public save.
 *
 * <p>Same six wire fields as the public upload chunk.
 */
public record GroupMediaUploadPacket(long uploadId, int index, int totalChunks,
                                     int totalBytes, String contentType, byte[] chunk) {

    // Read bounds aligned with MediaUploadPacket: a hostile client must not be
    // able to push oversized strings or chunks past the decoder.
    private static final int MAX_STRING_LEN = 128;

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(uploadId);
        buf.writeInt(index);
        buf.writeInt(totalChunks);
        buf.writeInt(totalBytes);
        buf.writeUtf(contentType != null ? contentType : "");
        buf.writeByteArray(chunk);
    }

    public static GroupMediaUploadPacket decode(FriendlyByteBuf buf) {
        return new GroupMediaUploadPacket(
            buf.readLong(), buf.readInt(), buf.readInt(), buf.readInt(),
            buf.readUtf(MAX_STRING_LEN), buf.readByteArray(DiskMediaStore.CHUNK_BYTES));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null) com.niuqu.chatbubble.server.MediaService.handleGroupUpload(
                sender, ChatServerListener.mediaStore(), uploadId, index, totalChunks, totalBytes,
                contentType, chunk);
        });
        ctx.get().setPacketHandled(true);
    }
}
