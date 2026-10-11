package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.chat.GroupChannelState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C reply to {@link GroupProtocolHelloPacket}. {@code protocol} echoes the
 * client's offer and {@code accepted} tells whether the server speaks it.
 *
 * <p>Only an accepted {@link GroupChannelState#GROUP_PROTOCOL} unlocks the v2
 * paths: an ACK for another version must not make the client consume a directory
 * it cannot interpret.
 */
public class GroupProtocolAckPacket {

    private final int protocol;
    private final boolean accepted;

    public GroupProtocolAckPacket(int protocol, boolean accepted) {
        this.protocol = protocol;
        this.accepted = accepted;
    }

    public int protocol() {
        return protocol;
    }

    public boolean accepted() {
        return accepted;
    }

    public static void encode(GroupProtocolAckPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.protocol);
        buf.writeBoolean(packet.accepted);
    }

    public static GroupProtocolAckPacket decode(FriendlyByteBuf buf) {
        return new GroupProtocolAckPacket(buf.readVarInt(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                GroupChannelState.onAck(protocol, accepted))
        );
        ctx.get().setPacketHandled(true);
    }
}
