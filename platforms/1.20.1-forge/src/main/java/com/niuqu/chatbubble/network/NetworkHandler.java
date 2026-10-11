package com.niuqu.chatbubble.network;
import com.niuqu.chatbubble.ChatBubbleMod;

import com.niuqu.chatbubble.packets.ChatMetaPacket;
import com.niuqu.chatbubble.packets.ConfigSyncPacket;
import com.niuqu.chatbubble.packets.ConfigSyncV2Packet;
import com.niuqu.chatbubble.packets.HistoryPacket;
import com.niuqu.chatbubble.packets.QuoteSyncPacket;
import com.niuqu.chatbubble.packets.ServerConfigSavePacket;
import com.niuqu.chatbubble.packets.ServerConfigScreenPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    /** Handshake version. Bump it whenever a packet's WIRE SHAPE changes:
     *  the handshake is the only guard against a mixed pair, and a stale
     *  number lets one connect and then silently drop undecodable packets.
     *  2.4.10 appended groupsEnabled to ServerConfigDto, so "1" already
     *  described a shape that no longer exists; it is kept only because
     *  2.4.13 ships no further field change and bumping here would reject
     *  a compatible 2.4.10-2.4.12 peer. Next shape change bumps to "2". */
    private static final String PROTOCOL = "1";
    /** Independent optional channel for the 2.5.0 group protocol. Separate from
     *  {@link #PROTOCOL} on purpose: enabling it must not change the main
     *  channel's handshake, and a 2.4.x peer simply never negotiates it. */
    private static final String GROUP_PROTOCOL = "1";
    public static SimpleChannel CHANNEL;
    public static SimpleChannel GROUPS_CHANNEL;

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ChatBubbleMod.MODID, "main"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL)
        );

        CHANNEL.messageBuilder(QuoteSyncPacket.class, 0)
            .encoder(QuoteSyncPacket::encode)
            .decoder(QuoteSyncPacket::decode)
            .consumerMainThread(QuoteSyncPacket::handle)
            .add();

        CHANNEL.messageBuilder(ChatMetaPacket.class, 1)
            .encoder(ChatMetaPacket::encode)
            .decoder(ChatMetaPacket::decode)
            .consumerMainThread(ChatMetaPacket::handle)
            .add();

        CHANNEL.messageBuilder(HistoryPacket.class, 2)
            .encoder(HistoryPacket::encode)
            .decoder(HistoryPacket::decode)
            .consumerMainThread(HistoryPacket::handle)
            .add();

        CHANNEL.messageBuilder(ConfigSyncPacket.class, 3)
            .encoder(ConfigSyncPacket::encode)
            .decoder(ConfigSyncPacket::decode)
            .consumerMainThread(ConfigSyncPacket::handle)
            .add();

        CHANNEL.messageBuilder(ConfigSyncV2Packet.class, 4)
            .encoder(ConfigSyncV2Packet::encode)
            .decoder(ConfigSyncV2Packet::decode)
            .consumerMainThread(ConfigSyncV2Packet::handle)
            .add();

        CHANNEL.messageBuilder(ServerConfigScreenPacket.class, 5)
            .encoder(ServerConfigScreenPacket::encode)
            .decoder(ServerConfigScreenPacket::decode)
            .consumerMainThread(ServerConfigScreenPacket::handle)
            .add();

        CHANNEL.messageBuilder(ServerConfigSavePacket.class, 6)
            .encoder(ServerConfigSavePacket::encode)
            .decoder(ServerConfigSavePacket::decode)
            .consumerMainThread(ServerConfigSavePacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.MediaUploadPacket.class, 7)
            .encoder(com.niuqu.chatbubble.packets.MediaUploadPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.MediaUploadPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.MediaUploadPacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.MediaRequestPacket.class, 8)
            .encoder(com.niuqu.chatbubble.packets.MediaRequestPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.MediaRequestPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.MediaRequestPacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.MediaCapPacket.class, 9)
            .encoder(com.niuqu.chatbubble.packets.MediaCapPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.MediaCapPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.MediaCapPacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.MediaUploadAckPacket.class, 10)
            .encoder(com.niuqu.chatbubble.packets.MediaUploadAckPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.MediaUploadAckPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.MediaUploadAckPacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.MediaResponsePacket.class, 11)
            .encoder(com.niuqu.chatbubble.packets.MediaResponsePacket::encode)
            .decoder(com.niuqu.chatbubble.packets.MediaResponsePacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.MediaResponsePacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.EasyBotConfigPacket.class, 12)
            .encoder(com.niuqu.chatbubble.packets.EasyBotConfigPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.EasyBotConfigPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.EasyBotConfigPacket::handle)
            .add();

        // 2.4.10 group chat: handshake / say / directory / manage (ids 13-16).
        // Old clients safely drop unknown ids; a new client against an old
        // server just never receives id 15, so the tab strip stays hidden.
        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.ClientHelloPacket.class, 13)
            .encoder(com.niuqu.chatbubble.packets.ClientHelloPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.ClientHelloPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.ClientHelloPacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupChatPacket.class, 14)
            .encoder(com.niuqu.chatbubble.packets.GroupChatPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupChatPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupChatPacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupListPacket.class, 15)
            .encoder(com.niuqu.chatbubble.packets.GroupListPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupListPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupListPacket::handle)
            .add();

        CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupActionPacket.class, 16)
            .encoder(com.niuqu.chatbubble.packets.GroupActionPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupActionPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupActionPacket::handle)
            .add();

        // 2.5.0 group protocol v2: its own optional channel (protocol "1",
        // acceptMissingOr) so the main channel's protocol and ids 0-16 stay
        // byte-identical. A vanilla or 2.4.x peer never negotiates it, and v1
        // group actions keep running over the main channel for those peers.
        GROUPS_CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ChatBubbleMod.MODID, "groups"),
            () -> GROUP_PROTOCOL,
            NetworkRegistry.acceptMissingOr(GROUP_PROTOCOL),
            NetworkRegistry.acceptMissingOr(GROUP_PROTOCOL)
        );

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupProtocolHelloPacket.class, 0)
            .encoder(com.niuqu.chatbubble.packets.GroupProtocolHelloPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupProtocolHelloPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupProtocolHelloPacket::handle)
            .add();

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupProtocolAckPacket.class, 1)
            .encoder(com.niuqu.chatbubble.packets.GroupProtocolAckPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupProtocolAckPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupProtocolAckPacket::handle)
            .add();

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupDirectoryPacket.class, 2)
            .encoder(com.niuqu.chatbubble.packets.GroupDirectoryPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupDirectoryPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupDirectoryPacket::handle)
            .add();

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupChatV2Packet.class, 3)
            .encoder(com.niuqu.chatbubble.packets.GroupChatV2Packet::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupChatV2Packet::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupChatV2Packet::handle)
            .add();

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupHistoryRequestPacket.class, 4)
            .encoder(com.niuqu.chatbubble.packets.GroupHistoryRequestPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupHistoryRequestPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupHistoryRequestPacket::handle)
            .add();

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupHistoryPagePacket.class, 5)
            .encoder(com.niuqu.chatbubble.packets.GroupHistoryPagePacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupHistoryPagePacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupHistoryPagePacket::handle)
            .add();

        // 2.5.0 lifecycle actions plus the chunked directory for a snapshot too
        // large for one packet. Group media (M9) claims the two free ids 7/8: the
        // per-upload group scope and the group-scoped upload path, so a group
        // image is stored as g_ bytes bound to its group UUID.
        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupActionV2Packet.class, 6)
            .encoder(com.niuqu.chatbubble.packets.GroupActionV2Packet::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupActionV2Packet::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupActionV2Packet::handle)
            .add();

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupMediaContextPacket.class, 7)
            .encoder(com.niuqu.chatbubble.packets.GroupMediaContextPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupMediaContextPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupMediaContextPacket::handle)
            .add();

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupMediaUploadPacket.class, 8)
            .encoder(com.niuqu.chatbubble.packets.GroupMediaUploadPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupMediaUploadPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupMediaUploadPacket::handle)
            .add();

        GROUPS_CHANNEL.messageBuilder(com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket.class, 9)
            .encoder(com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket::encode)
            .decoder(com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket::decode)
            .consumerMainThread(com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket::handle)
            .add();
    }
}
