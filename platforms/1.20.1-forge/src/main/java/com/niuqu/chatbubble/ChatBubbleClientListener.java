package com.niuqu.chatbubble;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.render.ChatBubbleScreen;
import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.render.ChatBubbleHudOverlay;
import com.niuqu.chatbubble.ui.BedScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class ChatBubbleClientListener {

    private static Screen screenBeforeSleep;

    @SubscribeEvent
    public void onScreenOpen(ScreenEvent.Opening event) {
        // Minecraft.<init> fires ScreenEvent.Opening before NeoForge/Forge have
        // loaded configs; ConfigValue.get() then throws IllegalStateException
        // ("Cannot get config value before config is loaded") and crashes the
        // game at startup. Until configs are loaded, leave screens untouched.
        try {
            if (!ChatBubbleConfig.ENABLED.get()) return;
        } catch (IllegalStateException e) {
            return;
        }
        if (event.getScreen() instanceof InBedChatScreen) {
            // Vanilla force-opens InBedChatScreen every tick while sleeping; swap in
            // our minimal bed screen so the Leave Bed button survives the chat rework
            event.setCanceled(true);
            Minecraft.getInstance().setScreen(new BedScreen());
        } else if (event.getScreen() instanceof ChatScreen chatScreen
                && !(chatScreen instanceof ChatBubbleScreen)) {
            event.setCanceled(true);
            String initial = getChatInitialText(chatScreen);
            Minecraft.getInstance().setScreen(new ChatBubbleScreen(initial));
        }
    }

    private static String getChatInitialText(ChatScreen chatScreen) {
        try {
            var f = ChatScreen.class.getDeclaredField("initial");
            f.setAccessible(true);
            String val = (String) f.get(chatScreen);
            return val != null ? val : "";
        } catch (Exception e) {
            // ignore
        }
        for (var f : ChatScreen.class.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                try {
                    String val = (String) f.get(chatScreen);
                    if (val != null && !val.isEmpty()) return val;
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    @SubscribeEvent
    public void onSleepStart(PlayerSleepInBedEvent event) {
        screenBeforeSleep = Minecraft.getInstance().screen;
    }

    @SubscribeEvent
    public void onWakeUp(PlayerWakeUpEvent event) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (screenBeforeSleep == null) {
                if (mc.screen instanceof ChatBubbleScreen) {
                    mc.setScreen(null);
                }
            }
            screenBeforeSleep = null;
        });
    }

    @SubscribeEvent
    public void onRenderGuiPre(RenderGuiEvent.Pre event) {
        // Translucent E33Chat screens hide the HUD by cancelling this event
        // instead of setting options.hideGui (which is the F1 flag and would
        // also hide the first-person hand/held item).
        if (com.niuqu.chatbubble.render.HudVisibility.shouldHideHud()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        if (!ChatBubbleConfig.ENABLED.get()) return;
        ChatBubbleHudOverlay.render(event.getGuiGraphics());
    }

    /**
     * The two login sends, resolved at call time. Kept as package-visible fields
     * (not direct calls in the hook) so a unit test can drive the real
     * {@link #onLoggingIn} hook and observe BOTH hellos without a live connection.
     */
    static Runnable helloV1Sender = com.niuqu.chatbubble.packets.ClientHelloPacket::send;
    static Runnable helloV2Sender = com.niuqu.chatbubble.packets.GroupProtocolHelloPacket::send;

    @SubscribeEvent
    public void onLoggingIn(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        // Fresh server = fresh group state; then announce the mod so the
        // server routes group chat as packets and pushes the group directory.
        com.niuqu.chatbubble.chat.GroupChannelState.reset();
        com.niuqu.chatbubble.store.ChatMessageStore.clearSeenPlayers();
        announceToServer();
    }

    /**
     * The login announce. Both hellos must go out on every login: the v1 hello
     * routes the 2.4.x group chat, and the v2 offer on its own channel is what
     * makes the server push the recipient-scoped directory the group browser
     * needs. Without the v2 offer the server never adds this player to
     * {@code groupV2Clients}, so {@code GroupManager.sendDirectory} returns early
     * and creating a group is a silent no-op (the M5 regression).
     *
     * <p>{@link com.niuqu.chatbubble.packets.GroupProtocolHelloPacket#send()} gates
     * itself on the peer's negotiated channels — Forge's
     * {@code SimpleChannel#isRemotePresent} is a plain boolean, so an unnegotiated
     * peer simply drops the send and no outer guard is needed (unlike NeoForge,
     * where an unnegotiated send hard-throws).
     */
    static void announceToServer() {
        // Forge's SimpleChannel#isRemotePresent is a plain boolean and
        // GroupProtocolHelloPacket.send() already drops an unnegotiated send, so the
        // v2 offer is always attempted; the shared gate carries the decision shape.
        com.niuqu.chatbubble.network.LoginHandshake.announce(true, helloV1Sender, helloV2Sender);
    }

    @SubscribeEvent
    public void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        com.niuqu.chatbubble.chat.GroupChannelState.reset();
        com.niuqu.chatbubble.render.HudVisibility.reset();
        // Animated GIFs hold one GPU texture per frame; a new server must not
        // inherit the old one's texture set.
        com.niuqu.chatbubble.image.AnimatedImageLoader.resetAll();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        com.niuqu.chatbubble.image.ImageLoader.tick();
        com.niuqu.chatbubble.image.AnimatedImageLoader.tick();
        Minecraft mc = Minecraft.getInstance();
        String key;
        if (mc.level == null || mc.player == null) {
            key = null;
        } else if (mc.getSingleplayerServer() != null) {
            key = "SP:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else if (mc.getCurrentServer() != null) {
            key = "MP:" + mc.getCurrentServer().name;
        } else {
            key = "world";
        }
        ChatMessageStore.setCurrentWorld(key);
        ChatMessageStore.maybeAutoSave();
    }

    @SubscribeEvent
    public void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (!ChatBubbleConfig.ENABLED.get()) return;
        if (event.getButton() != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        double mx = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getScreenHeight();
        if (ChatBubbleHudOverlay.isMouseOverIcon(mx, my)) {
            event.setCanceled(true);
            mc.setScreen(new ChatBubbleScreen(""));
        }
    }
}
