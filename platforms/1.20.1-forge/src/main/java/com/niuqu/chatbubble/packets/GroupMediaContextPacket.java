package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.server.MediaService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -&gt; server: the group a media upload belongs to, sent immediately before
 * the first {@link MediaUploadPacket} chunk of the same uploadId (2.5.0 M9 group
 * media).
 *
 * <p>The actor is the real receiving connection, never a value off the wire: the
 * server checks that connection's current group membership and records the scope
 * for that connection UUID + uploadId. If the group is gone or the sender is not
 * (or no longer) a member, the scope is remembered as denied so the following
 * chunks are refused instead of being stored as an ordinary public upload.
 *
 * <p>The group is named by its stable <em>UUID</em> (the frozen model, §13 D6).
 */
public record GroupMediaContextPacket(UUID groupId, long uploadId) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(groupId != null ? groupId : new UUID(0, 0));
        buf.writeLong(uploadId);
    }

    public static GroupMediaContextPacket decode(FriendlyByteBuf buf) {
        return new GroupMediaContextPacket(buf.readUUID(), buf.readLong());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null) {
                MediaService.prepareGroupUploadContext(sender, groupId, uploadId);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
