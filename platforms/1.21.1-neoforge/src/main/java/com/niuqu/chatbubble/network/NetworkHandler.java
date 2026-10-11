package com.niuqu.chatbubble.network;
import com.niuqu.chatbubble.ChatBubbleMod;

import com.niuqu.chatbubble.packets.ChatMetaPayload;
import com.niuqu.chatbubble.packets.ClientServerConfigGui;
import com.niuqu.chatbubble.packets.ClientHelloPayload;
import com.niuqu.chatbubble.packets.ConfigSyncPayload;
import com.niuqu.chatbubble.packets.GroupActionPayload;
import com.niuqu.chatbubble.packets.GroupChatPayload;
import com.niuqu.chatbubble.packets.GroupListPayload;
import com.niuqu.chatbubble.packets.ConfigSyncV2Payload;
import com.niuqu.chatbubble.packets.EasyBotConfigPayload;
import com.niuqu.chatbubble.packets.HistoryPayload;
import com.niuqu.chatbubble.packets.MediaCapPayload;
import com.niuqu.chatbubble.packets.MediaRequestPayload;
import com.niuqu.chatbubble.packets.MediaResponsePayload;
import com.niuqu.chatbubble.packets.MediaUploadAckPayload;
import com.niuqu.chatbubble.packets.MediaUploadPayload;
import com.niuqu.chatbubble.packets.QuoteSyncPayload;
import com.niuqu.chatbubble.packets.ServerConfigSavePayload;
import com.niuqu.chatbubble.packets.ServerConfigScreenPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional: joining servers without this mod must not be rejected.
        // The version string follows Forge's PROTOCOL: bump it whenever a
        // packet's wire shape changes, or a mixed pair connects and then
        // silently drops undecodable packets. See NetworkHandler there.
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(QuoteSyncPayload.TYPE, QuoteSyncPayload.STREAM_CODEC, QuoteSyncPayload::handleServer);
        registrar.playToClient(ChatMetaPayload.TYPE, ChatMetaPayload.STREAM_CODEC, ChatMetaPayload::handleClient);
        registrar.playToClient(HistoryPayload.TYPE, HistoryPayload.STREAM_CODEC, HistoryPayload::handleClient);
        registrar.playToClient(ConfigSyncPayload.TYPE, ConfigSyncPayload.STREAM_CODEC, ConfigSyncPayload::handleClient);
        registrar.playToClient(ConfigSyncV2Payload.TYPE, ConfigSyncV2Payload.STREAM_CODEC, ConfigSyncV2Payload::handleClient);
        registrar.playToClient(EasyBotConfigPayload.TYPE, EasyBotConfigPayload.STREAM_CODEC, EasyBotConfigPayload::handleClient);
        // Lambda (not a method reference): a method reference resolves its target
        // class eagerly at registration, which would load the client-only GUI class
        // on a dedicated server and trip FML's RuntimeDistCleaner. The lambda body
        // runs only when a client actually receives the packet, so the client-side
        // reference never touches server class loading.
        registrar.playToClient(ServerConfigScreenPayload.TYPE, ServerConfigScreenPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
                    ClientServerConfigGui.open(payload, ctx);
                }
            });
        registrar.playToServer(ServerConfigSavePayload.TYPE, ServerConfigSavePayload.STREAM_CODEC, ServerConfigSavePayload::handleServer);
        registrar.playToServer(MediaUploadPayload.TYPE, MediaUploadPayload.STREAM_CODEC, MediaUploadPayload::handleServer);
        registrar.playToServer(MediaRequestPayload.TYPE, MediaRequestPayload.STREAM_CODEC, MediaRequestPayload::handleServer);
        // Client-only handlers must not load on a dedicated server: use dist-guarded
        // lambdas (MediaClient references the client). See the ServerConfigScreenPayload
        // comment above for why a method reference would trip RuntimeDistCleaner.
        registrar.playToClient(MediaUploadAckPayload.TYPE, MediaUploadAckPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
                    com.niuqu.chatbubble.image.MediaClient.handleAck(payload, ctx);
                }
            });
        registrar.playToClient(MediaResponsePayload.TYPE, MediaResponsePayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
                    com.niuqu.chatbubble.image.MediaClient.handleResponse(payload, ctx);
                }
            });
        registrar.playToClient(MediaCapPayload.TYPE, MediaCapPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
                    MediaCapPayload.handleClient(payload, ctx);
                }
            });
        // 2.4.10 group chat: handshake / say / directory / manage. Old clients
        // drop unknown payloads harmlessly; a new client against an old server
        // just never receives group_list, so the tab strip stays hidden.
        registrar.playToServer(ClientHelloPayload.TYPE, ClientHelloPayload.STREAM_CODEC, ClientHelloPayload::handleServer);
        registrar.playToClient(GroupChatPayload.TYPE, GroupChatPayload.STREAM_CODEC, GroupChatPayload::handleClient);
        registrar.playToClient(GroupListPayload.TYPE, GroupListPayload.STREAM_CODEC, GroupListPayload::handleClient);
        registrar.playToServer(GroupActionPayload.TYPE, GroupActionPayload.STREAM_CODEC, GroupActionPayload::handleServer);
        // 2.5.0 group protocol v2, also optional: only a peer that declared these ids
        // receives them. The v1 ids above stay registered so a 2.4.x peer keeps
        // negotiating the main channel unchanged; the server answers a v1 action with
        // "update your client" instead of the removed self-service join.
        registrar.playToServer(com.niuqu.chatbubble.packets.GroupProtocolHelloPayload.TYPE,
            com.niuqu.chatbubble.packets.GroupProtocolHelloPayload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupProtocolHelloPayload::handleServer);
        registrar.playToClient(com.niuqu.chatbubble.packets.GroupProtocolAckPayload.TYPE,
            com.niuqu.chatbubble.packets.GroupProtocolAckPayload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupProtocolAckPayload::handleClient);
        registrar.playToClient(com.niuqu.chatbubble.packets.GroupDirectoryPayload.TYPE,
            com.niuqu.chatbubble.packets.GroupDirectoryPayload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupDirectoryPayload::handleClient);
        registrar.playToClient(com.niuqu.chatbubble.packets.GroupChatV2Payload.TYPE,
            com.niuqu.chatbubble.packets.GroupChatV2Payload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupChatV2Payload::handleClient);
        registrar.playToServer(com.niuqu.chatbubble.packets.GroupHistoryRequestPayload.TYPE,
            com.niuqu.chatbubble.packets.GroupHistoryRequestPayload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupHistoryRequestPayload::handleServer);
        registrar.playToClient(com.niuqu.chatbubble.packets.GroupHistoryPagePayload.TYPE,
            com.niuqu.chatbubble.packets.GroupHistoryPagePayload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupHistoryPagePayload::handleClient);
        registrar.playToServer(com.niuqu.chatbubble.packets.GroupActionV2Payload.TYPE,
            com.niuqu.chatbubble.packets.GroupActionV2Payload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupActionV2Payload::handleServer);
        // 2.5.0 M9 group media: the per-upload group scope plus the group-scoped
        // upload path. The public main-channel media upload is untouched; group
        // images travel here so their g_ bytes are scoped to their group UUID.
        registrar.playToServer(com.niuqu.chatbubble.packets.GroupMediaContextPayload.TYPE,
            com.niuqu.chatbubble.packets.GroupMediaContextPayload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupMediaContextPayload::handleServer);
        registrar.playToServer(com.niuqu.chatbubble.packets.GroupMediaUploadPayload.TYPE,
            com.niuqu.chatbubble.packets.GroupMediaUploadPayload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupMediaUploadPayload::handleServer);
        // Large recipient-scoped directories travel as chunks on the same protocol;
        // small ones keep using the single GroupDirectoryPayload above.
        registrar.playToClient(com.niuqu.chatbubble.packets.GroupDirectoryChunkPayload.TYPE,
            com.niuqu.chatbubble.packets.GroupDirectoryChunkPayload.STREAM_CODEC,
            com.niuqu.chatbubble.packets.GroupDirectoryChunkPayload::handleClient);
    }
}
