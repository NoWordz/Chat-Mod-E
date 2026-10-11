package com.niuqu.chatbubble.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C reply to {@link GroupProtocolHelloPacket}. {@code protocol} echoes the
 * client's offer and {@code accepted} tells whether the server speaks it.
 *
 * <p>Only an accepted protocol 1 unlocks the v2
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
        // TODO(M5): the client group state machine (GroupChannelState.onAck in the
        // M6-owned chat package) consumes this ACK; the v2 client UI lands in M5.
        ctx.get().setPacketHandled(true);
    }
}
