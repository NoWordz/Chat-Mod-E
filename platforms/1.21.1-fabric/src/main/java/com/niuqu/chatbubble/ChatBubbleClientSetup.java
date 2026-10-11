package com.niuqu.chatbubble;
import com.niuqu.chatbubble.config.ChatBubbleConfigScreen;
import com.niuqu.chatbubble.render.ChatBubbleHudOverlay;
import com.niuqu.chatbubble.render.RoundRectRenderer;

import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.config.ServerConfigScreen;
import com.niuqu.chatbubble.config.ConfigManager;
import com.niuqu.chatbubble.image.ImageLoader;
import com.niuqu.chatbubble.network.ChatMetaPayload;
import com.niuqu.chatbubble.network.ConfigSyncPayload;
import com.niuqu.chatbubble.network.ConfigSyncV2Payload;
import com.niuqu.chatbubble.network.EasyBotConfigPayload;
import com.niuqu.chatbubble.network.HistoryPayload;
import com.niuqu.chatbubble.network.MediaCapPayload;
import com.niuqu.chatbubble.network.ServerConfigScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

public class ChatBubbleClientSetup implements ClientModInitializer {
    private static ChatBubbleConfig config = ChatBubbleConfig.defaults();
    private static Path configPath;
    private static boolean leftWasDown;

    public static ChatBubbleConfig config() { return config; }

    public static void saveConfig(ChatBubbleConfig newConfig) {
        config = newConfig;
        com.mojang.logging.LogUtils.getLogger().info("[e33chat] Saving config | soundPublic=" + newConfig.soundPublic() + " | soundSystem=" + newConfig.soundSystem());
        ConfigManager.save(configPath, config);
    }

    /**
     * The two login sends, resolved at call time. Kept as package-visible fields
     * (not direct calls in the join hook) so a unit test can drive the real
     * announce and observe BOTH hellos without a live connection.
     */
    static Runnable helloV1Sender = com.niuqu.chatbubble.network.ClientHelloPayload::send;
    static Runnable helloV2Sender = com.niuqu.chatbubble.network.GroupProtocolHelloPayload::send;

    /**
     * The login/join announce, shared shape with the Forge and NeoForge hooks so
     * the three loaders cannot drift: the v1 hello always, and the v2
     * group-protocol offer only when the peer declared that channel. The v2 offer
     * is what makes the server push the recipient-scoped directory the group
     * browser needs; without it, creating a group is a silent no-op.
     */
    static void announceOnJoin(boolean groupsChannelPresent) {
        com.niuqu.chatbubble.network.LoginHandshake.announce(
            groupsChannelPresent, helloV1Sender, helloV2Sender);
    }

    @Override
    public void onInitializeClient() {
        Path configDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/e33chat");
        configPath = configDir.resolve("e33chat-client.json");
        // Migration chain for the client config path (most recent first):
        // config/e33chat/client.json -> config/e33chat/e33chat-client.json
        // config/e33chat-client.json (2.3.1+) and config/e33chat.json (legacy) also move here.
        var logger = com.mojang.logging.LogUtils.getLogger();
        Path recentDirPath = configDir.resolve("client.json");
        Path legacyPath = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/e33chat-client.json");
        Path oldFlatPath = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/e33chat.json");
        if (!Files.exists(configPath)) {
            if (Files.exists(recentDirPath)) {
                try {
                    Files.move(recentDirPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    logger.info("[e33chat] Migrated config from config/e33chat/client.json to config/e33chat/e33chat-client.json");
                } catch (Exception e) {
                    logger.warn("[e33chat] Config migration failed", e);
                }
            } else if (Files.exists(legacyPath)) {
                try {
                    Files.createDirectories(configDir);
                    Files.move(legacyPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    logger.info("[e33chat] Migrated config from config/e33chat-client.json to config/e33chat/e33chat-client.json");
                } catch (Exception e) {
                    logger.warn("[e33chat] Config migration failed", e);
                }
            } else if (Files.exists(oldFlatPath)) {
                config = ConfigManager.load(oldFlatPath);
                ConfigManager.save(configPath, config);
                try {
                    Files.delete(oldFlatPath);
                } catch (Exception e) {
                    logger.warn("[e33chat] Legacy config cleanup failed", e);
                }
                logger.info("[e33chat] Migrated config from config/e33chat.json to config/e33chat/e33chat-client.json");
            }
        }
        // Load is unconditional once the new path exists — the static field
        // starts as defaults() (non-null), so a null check can never trigger.
        if (Files.exists(configPath)) {
            config = ConfigManager.load(configPath);
        } else if (Files.exists(recentDirPath)) {
            // Migration move failed earlier (locked/IO) — read in place so
            // settings are never silently replaced by defaults.
            config = ConfigManager.load(recentDirPath);
        } else if (Files.exists(legacyPath)) {
            config = ConfigManager.load(legacyPath);
        } else if (Files.exists(oldFlatPath)) {
            config = ConfigManager.load(oldFlatPath);
        } else {
            config = ConfigManager.load(configPath);
        }

        ClientPlayNetworking.registerGlobalReceiver(ChatMetaPayload.ID, (payload, context) -> {
            context.client().execute(() -> ChatMessageStore.applyChatMeta(
                payload.senderUUID(), payload.senderName(), payload.messageHash(),
                payload.quoteSender(), payload.quoteContent(), payload.mentionTargets()));
        });
        ClientPlayNetworking.registerGlobalReceiver(HistoryPayload.ID, (payload, context) -> {
            context.client().execute(() -> ChatMessageStore.addHistoryMessages(payload.entries()));
        });
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> ConfigSyncPayload.handle(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncV2Payload.ID, (payload, context) -> {
            context.client().execute(() -> ConfigSyncV2Payload.handle(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(EasyBotConfigPayload.ID, (payload, context) -> {
            context.client().execute(() -> EasyBotConfigPayload.handle(payload));
        });
        // Server-config GUI: opened on the client only (server never loads the Screen)
        ClientPlayNetworking.registerGlobalReceiver(ServerConfigScreenPayload.ID, (payload, context) -> {
            context.client().execute(() -> MinecraftClient.getInstance().setScreen(new ServerConfigScreen(
                MinecraftClient.getInstance().currentScreen,
                payload.useTpa(), payload.historyEnabled(), payload.templateDebug(),
                payload.mediaEnabled(), payload.mediaAutoClean(), payload.easyBotCompat(),
                payload.groupsEnabled(),
                payload.chatTemplates(), payload.whisperTemplates())));
        });

        com.niuqu.chatbubble.image.MediaClient.registerReceivers();

        // 2.4.10 group chat: announce the mod on join (server routes group chat
        // as payloads + pushes the group directory); reset state on disconnect.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            com.niuqu.chatbubble.chat.GroupChannelState.reset();
            com.niuqu.chatbubble.store.ChatMessageStore.clearSeenPlayers();
            // 2.5.0 v2 handshake: real login hook, and only when the server declared
            // the channel — an old server never does, so the v1 flow stays intact.
            announceOnJoin(
                ClientPlayNetworking.canSend(com.niuqu.chatbubble.network.GroupProtocolHelloPayload.ID));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            com.niuqu.chatbubble.chat.GroupChannelState.reset();
            // Capture state (pending notes, echo counters, quote timestamps) is
            // connection-scoped: a new server must not inherit the old one's.
            // Animated GIFs hold one GPU texture per frame; a new server must
            // not inherit the old one's texture set. Static images and in-flight
            // media transfers go the same way.
            com.niuqu.chatbubble.image.AnimatedImageLoader.resetAll();
            // A partially received directory transfer must not survive the session.
            com.niuqu.chatbubble.network.GroupDirectoryChunkPayload.resetTransfers();
        });
        ClientPlayNetworking.registerGlobalReceiver(com.niuqu.chatbubble.network.GroupChatPayload.ID, (payload, context) -> {
            context.client().execute(() -> com.niuqu.chatbubble.network.GroupChatPayload.handleClient(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(com.niuqu.chatbubble.network.GroupListPayload.ID, (payload, context) -> {
            context.client().execute(() -> com.niuqu.chatbubble.network.GroupListPayload.handleClient(payload));
        });
        // 2.5.0 v2 consumers: ACK unlocks, directory drives the paging chain, live
        // rows are deduplicated against the pages by (groupId, sequence).
        ClientPlayNetworking.registerGlobalReceiver(com.niuqu.chatbubble.network.GroupProtocolAckPayload.ID, (payload, context) -> {
            context.client().execute(() -> com.niuqu.chatbubble.network.GroupProtocolAckPayload.handleClient(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(com.niuqu.chatbubble.network.GroupDirectoryPayload.ID, (payload, context) -> {
            context.client().execute(() -> com.niuqu.chatbubble.network.GroupDirectoryPayload.handleClient(payload));
        });
        // A directory larger than one custom payload arrives as chunks; the client
        // reassembles them and applies the same single snapshot on the last chunk.
        ClientPlayNetworking.registerGlobalReceiver(com.niuqu.chatbubble.network.GroupDirectoryChunkPayload.ID, (payload, context) -> {
            context.client().execute(() -> com.niuqu.chatbubble.network.GroupDirectoryChunkPayload.handleClient(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(com.niuqu.chatbubble.network.GroupChatV2Payload.ID, (payload, context) -> {
            context.client().execute(() -> com.niuqu.chatbubble.network.GroupChatV2Payload.handleClient(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(com.niuqu.chatbubble.network.GroupHistoryPagePayload.ID, (payload, context) -> {
            context.client().execute(() -> com.niuqu.chatbubble.network.GroupHistoryPagePayload.handleClient(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(MediaCapPayload.ID, (payload, context) -> {
            context.client().execute(() -> MediaCapPayload.handle(payload));
        });

        ChatMessageStore.setMessageEffectObserver(
            new com.niuqu.chatbubble.chat.notification.ChatMessageEffects());

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (!config.enabled()) return;
            ChatBubbleHudOverlay.render(drawContext);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ImageLoader.tick();
            com.niuqu.chatbubble.image.AnimatedImageLoader.tick();
            // 纹理全部走 drawTexture(Identifier) 懒加载（getTexture 自动 new ResourceTexture），F3+T 重载后自动重读资源包新 PNG
            if (!config.enabled()) return;

            String key;
            if (client.world == null || client.player == null) {
                key = null;
            } else if (client.getServer() != null) {
                key = "SP:" + client.getServer().getSaveProperties().getLevelName();
            } else if (client.getCurrentServerEntry() != null) {
                key = "MP:" + client.getCurrentServerEntry().name;
            } else {
                key = "world";
            }
            ChatMessageStore.setCurrentWorld(key);
            ChatMessageStore.maybeAutoSave();

            if (client.currentScreen == null) {
                boolean leftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                    client.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_1) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                if (leftDown && !leftWasDown) {
                    double mx = client.mouse.getX() * (double)client.getWindow().getScaledWidth() / (double)client.getWindow().getWidth();
                    double my = client.mouse.getY() * (double)client.getWindow().getScaledHeight() / (double)client.getWindow().getHeight();
                    if (ChatBubbleHudOverlay.isMouseOverIcon(mx, my)) {
                        client.setScreen(new ChatBubbleScreen(""));
                    }
                }
                leftWasDown = leftDown;
            } else {
                leftWasDown = false;
            }
        });

        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
            ScreenEvents.afterRender(screen).register((scr, g, mouseX, mouseY, delta) -> {
                if (config.enabled()) ChatBubbleHudOverlay.renderBannerForScreen(g);
            })
        );

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override
                public Identifier getFabricId() {
                    return Identifier.of(ChatBubbleMod.MOD_ID, "shader_reload");
                }
                @Override
                public void reload(ResourceManager manager) {
                    RoundRectRenderer.resetShader();
                }
            }
        );
    }
}
