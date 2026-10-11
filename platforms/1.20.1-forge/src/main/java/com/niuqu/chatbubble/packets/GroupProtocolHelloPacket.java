package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.chat.GroupChannelState;
import com.niuqu.chatbubble.network.NetworkHandler;
import com.niuqu.chatbubble.server.GroupManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S v2 group handshake. Sent right after login, on the dedicated
 * {@code e33chat:groups} channel, in addition to (never instead of) the v1
 * {@link ClientHelloPacket}: an old server ignores this channel entirely and
 * keeps serving the v1 group actions.
 *
 * <p>The server answers with a {@link GroupProtocolAckPacket} and, only for an
 * accepted protocol, the recipient-scoped directory.
 */
public class GroupProtocolHelloPacket {

    private final int protocol;

    public GroupProtocolHelloPacket(int protocol) {
        this.protocol = protocol;
    }

    public int protocol() {
        return protocol;
    }

    public static void encode(GroupProtocolHelloPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.protocol);
    }

    public static GroupProtocolHelloPacket decode(FriendlyByteBuf buf) {
        return new GroupProtocolHelloPacket(buf.readVarInt());
    }

    /** Client-side send. Only the login hook calls this, and only when the peer
     *  actually has the groups channel (a vanilla server would drop it anyway). */
    public static void send() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        var connection = mc.player.connection.getConnection();
        if (connection == null || !NetworkHandler.GROUPS_CHANNEL.isRemotePresent(connection)) return;
        NetworkHandler.GROUPS_CHANNEL.sendToServer(new GroupProtocolHelloPacket(GroupChannelState.GROUP_PROTOCOL));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                // From here on the server may address v2 payloads to this connection.
                com.niuqu.chatbubble.network.GroupNetworkHandler.trackV2Connection(
                    player.getUUID(), ctx.get().getNetworkManager());
                GroupManager.onGroupProtocolHello(player, protocol);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
