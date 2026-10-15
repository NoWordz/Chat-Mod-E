package com.niuqu.chatbubble.render;
import com.niuqu.chatbubble.logic.SidebarModel;
import com.niuqu.chatbubble.render.ChatBubbleScreen;

import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.chat.GroupChannelState;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.texture.UiElement;
import com.niuqu.chatbubble.texture.UiTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public final class ChatSidebar {

    public static final int WIDTH = 90;
    private static final int ITEM_H = 22;
    private static final int ICON_S = 20;
    private static final int SEARCH_H = 14;
    private ChatSidebar() {}

    // ---- Hit testing (called from ChatBubbleScreen) ----

    public static boolean handleMouseClicked(double mouseX, double mouseY,
                                              String whisperPartner, Font font,
                                              int screenX, boolean visible,
                                              EditBox searchBox, int scrollOffset, int groupScroll,
                                              int columnBottom) {
        if (!visible) return false;
        int localX = (int)mouseX - screenX;
        if (localX < 0 || localX > WIDTH) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return false;

        // Search box
        if (mouseY >= 2 && mouseY <= 2 + SEARCH_H) return true;
        if (mouseY >= SidebarModel.PUBLIC_Y && mouseY <= SidebarModel.PUBLIC_Y + ITEM_H) return true;

        // Group rows (the 「我的群」 caption above them is not a target) and the pinned
        // create button — geometry from SidebarModel, shared by all targets.
        int listEnd = SidebarModel.listBottom(columnBottom);
        int groupCount = groupCount();
        int firstRow = SidebarModel.groupFirstRow(groupCount, groupScroll);
        int lastRow = SidebarModel.groupWindowEnd(groupCount, firstRow);
        for (int i = firstRow; i < lastRow; i++) {
            int gy = SidebarModel.groupWindowRowY(groupCount, i, firstRow);
            if (gy + ITEM_H > listEnd) break; // drawn clipped there too
            if (mouseY >= gy && mouseY <= gy + ITEM_H) return true;
        }
        int btnY = SidebarModel.createButtonY(columnBottom);
        if (mouseY >= btnY && mouseY <= btnY + SidebarModel.CREATE_BTN_H
            && localX >= SidebarModel.createButtonX()
            && localX <= SidebarModel.createButtonX() + SidebarModel.createButtonW()) return true;

        var players = new ArrayList<>(mc.player.connection.getOnlinePlayers());
        String selfName = mc.player.getName().getString();
        String filter = searchBox.getValue().toLowerCase().trim();
        int startY = SidebarModel.playerStartY(groupCount);
        int scrollY = startY - scrollOffset;
        for (var info : players) {
            String name = info.getProfile().getName();
            if (name.equals(selfName)) continue;
            if (ChatBubbleConfig.isSidebarHidden(name)) continue;
            if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
            // Clip to the visible band: the half-row above startY and the half-row past
            // the create button are drawn clipped, so their visible part reacts.
            if (scrollY + ITEM_H > startY && scrollY < listEnd
                && mouseY >= Math.max(scrollY, startY)
                && mouseY <= Math.min(scrollY + ITEM_H, listEnd)) return true;
            scrollY += ITEM_H + 2;
        }
        return false;
    }

    /** Sidebar group rows in order: v2 groups first, then pre-2.5 (v1) legacy tabs. */
    public static List<String> groupTabs() {
        List<String> out = new ArrayList<>();
        for (var entry : GroupChannelState.directory().values())
            out.add(entry.identity().id().toString());
        out.addAll(GroupChannelState.legacyTabs());
        return out;
    }

    private static int groupCount() { return groupTabs().size(); }

    /** True when the local player owns the group behind this tab key (legacy → false). */
    public static boolean ownsGroup(String tab) {
        Minecraft mc = Minecraft.getInstance();
        UUID id = uuidOrNull(tab);
        if (mc.player == null || id == null) return false;
        var entry = GroupChannelState.directoryEntry(id);
        return entry != null && mc.player.getUUID().equals(entry.ownerId());
    }

    private static UUID uuidOrNull(String tab) {
        try {
            return UUID.fromString(tab);
        } catch (RuntimeException notAUuid) {
            return null; // pre-2.5 tabs are keyed by display name
        }
    }

    // ---- Rendering (called from ChatBubbleScreen) ----

    public static int render(GuiGraphics g, Font font, int mouseX, int mouseY,
                              ChatBubbleTheme.Colors c, int panelW,
                              String whisperPartner, ResourceLocation publicIcon,
                              ResourceLocation groupIcon,
                              ResourceLocation noOnlineIcon, ResourceLocation privateTipIcon,
                              EditBox searchBox, int scrollOffset, int groupScroll,
                              int prevMaxScroll, float alpha,
                              int screenH) {
        // Background/divider span the real screen height: a hardcoded cap
        // disappears below y=999 on large GUIs (scale 1 at 1080p+).
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_BG), 0, 0, WIDTH, screenH, alpha);
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), WIDTH - 1, 0, 1, screenH, alpha);

        Minecraft mc = Minecraft.getInstance();
        int y = 2;

        // Search box
        int sbx = 2;
        int sby = 2;
        int sbw = WIDTH - 5;
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.INPUT_BG), sbx - 1, sby, sbw + 1, SEARCH_H, alpha);
        boolean hoverSearch = mouseX >= sbx - 1 && mouseX <= sbx + sbw
            && mouseY >= sby && mouseY <= sby + SEARCH_H;
        if (hoverSearch || searchBox.isFocused())
            g.renderOutline(sbx - 1, sby, sbw + 1, SEARCH_H, c.textMuted());
        if (searchBox.getValue().isEmpty() && !searchBox.isFocused()) {
            g.drawString(font, Component.translatable("e33chat.sidebar.search"),
                sbx, sby + 3, c.textMuted(), false);
        }
        y = sby + SEARCH_H + 3;

        // Public tab: lit while the view is neither a whisper nor a group tab. It must stay
        // lit in 全部 (the default view) — gating it on TAB_WORLD made the sidebar look
        // unselected right after joining.
        String activeNow = GroupChannelState.active();
        boolean isPublic = whisperPartner == null && !groupTabs().contains(activeNow);
        boolean hoverTab = mouseX >= 0 && mouseX <= WIDTH && mouseY >= y && mouseY <= y + ITEM_H;
        if (isPublic)
            com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_SELECTED), 0, y, WIDTH, ITEM_H, alpha);
        else if (hoverTab)
            com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_HOVER), 0, y, WIDTH, ITEM_H, alpha);
        drawIcon(g, publicIcon, 2, y + 1, ICON_S, alpha);
        int nameX = 2 + ICON_S + 3;
        String publicLabel = Component.translatable("e33chat.sidebar.public").getString();
        g.drawString(font, Component.literal(publicLabel), nameX, y + 1, c.textPrimary(), false);
        ChatMessageStore.ChatMessage latestPub =
            ChatMessageStore.getLatestForTab(GroupChannelState.TAB_WORLD);
        if (latestPub != null) {
            int previewMaxW = WIDTH - nameX - 4;
            String preview = ChatMessageStore.singleLine(latestPub.content().getString());
            String previewDisplay = font.plainSubstrByWidth(preview, previewMaxW - font.width("..."));
            if (!previewDisplay.equals(preview)) previewDisplay += "...";
            g.drawString(font, Component.literal(previewDisplay),
                nameX, y + 1 + font.lineHeight, c.textMuted(), false);
        }
        y += ITEM_H + 2;

        // ---- Group rows (2.5.0: groups left the top tab strip for this column).
        // All geometry comes from SidebarModel, so the three targets cannot drift. The
        // block shows at most SIDEBAR_GROUP_MAX_ROWS rows and scrolls beyond that.
        // The column runs the full window height (see the background fill above), so its
        // bottom edge is screenH. That is the anchor for both the list bottom and the
        // pinned create button: anchoring on the message-area bottom instead left the
        // button floating a full bar's height above the window edge.
        int columnBottom = screenH;
        int listEnd = SidebarModel.listBottom(columnBottom);
        int groupCount = groupCount();
        if (groupCount > 0) {
            List<String> groups = groupTabs();
            String caption = Component.translatable("e33chat.group.my_groups_header").getString();
            int captionY = SidebarModel.groupTitleY(groupCount);
            if (captionY + SidebarModel.GROUP_TITLE_H <= listEnd)
                g.drawString(font, Component.literal(caption), 4, captionY, c.textMuted(), false);
            String activeTab = GroupChannelState.active();
            // Row-granular scroll window: at most SIDEBAR_GROUP_MAX_ROWS rows are shown,
            // and the create row below the window never moves.
            int firstRow = SidebarModel.groupFirstRow(groupCount, groupScroll);
            int lastRow = SidebarModel.groupWindowEnd(groupCount, firstRow);
            for (int i = firstRow; i < lastRow; i++) {
                String tab = groups.get(i);
                String label = GroupChannelState.tabDisplayName(tab);
                if (label == null) label = tab;
                int ry = SidebarModel.groupWindowRowY(groupCount, i, firstRow);
                if (ry + ITEM_H > listEnd) break; // panel too short for even one row
                // A whisper view owns the list: the group row must not stay lit behind it.
                boolean sel = whisperPartner == null && tab.equals(activeTab);
                boolean hoverRow = mouseX >= 0 && mouseX <= WIDTH
                    && mouseY >= ry && mouseY <= ry + ITEM_H;
                if (sel)
                    com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_SELECTED), 0, ry, WIDTH, ITEM_H, alpha);
                else if (hoverRow)
                    com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_HOVER), 0, ry, WIDTH, ITEM_H, alpha);
                // Same left column as the public row, so every label lines up at nameX.
                drawIcon(g, groupIcon, 2, ry + 1, ICON_S, alpha);
                // Legacy (v1) tabs have no roster, so they carry no role badge.
                boolean typed = uuidOrNull(tab) != null;
                String role = Component.translatable(ownsGroup(tab)
                    ? "e33chat.sidebar.role_owner" : "e33chat.sidebar.role_member").getString();
                int roleW = typed ? font.width(role) + 4 : 0;
                int maxLabelW = Math.max(8, WIDTH - nameX - 4 - roleW);
                String display = font.plainSubstrByWidth(label, maxLabelW);
                if (!display.equals(label))
                    display = font.plainSubstrByWidth(label, Math.max(4, maxLabelW - font.width("..."))) + "...";
                g.drawString(font, Component.literal(display), nameX, ry + 1, c.textPrimary(), false);
                // Per-group preview, same two-line pattern as the 世界频道 row above.
                ChatMessageStore.ChatMessage latestGroup = ChatMessageStore.getLatestForTab(tab);
                if (latestGroup != null) {
                    String gp = ChatMessageStore.singleLine(latestGroup.content().getString());
                    String gpShown = font.plainSubstrByWidth(gp, maxLabelW - font.width("..."));
                    if (!gpShown.equals(gp)) gpShown += "...";
                    g.drawString(font, Component.literal(gpShown), nameX, ry + 1 + font.lineHeight,
                        c.textMuted(), false);
                }
                if (typed)
                    g.drawString(font, Component.literal(role), WIDTH - 4 - font.width(role),
                        ry + 1, c.textMuted(), false);
            }
        }

        int newMaxScroll = prevMaxScroll;
        if (mc.player != null && mc.player.connection != null) {
            var players = new ArrayList<>(mc.player.connection.getOnlinePlayers());
            String selfName = mc.player.getName().getString();
            String filter = searchBox.getValue().toLowerCase().trim();

            int startY = SidebarModel.playerStartY(groupCount);
            int listEnd2 = listEnd;
            int totalH = 0;
            for (var info : players) {
                String name = info.getProfile().getName();
                if (name.equals(selfName)) continue;
                if (ChatBubbleConfig.isSidebarHidden(name)) continue;
                if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
                totalH += ITEM_H + 2;
            }

            if (totalH == 0) {
                // Only draw the empty state while it actually fits inside the list area.
                if (startY + 8 + 32 + 4 <= listEnd2) {
                    drawIcon(g, noOnlineIcon, (WIDTH - 32) / 2, startY + 8, 32, alpha);
                    String noPlayers = Component.translatable("e33chat.sidebar.no_players").getString();
                    int textW = font.width(noPlayers);
                    g.drawString(font, Component.literal(noPlayers),
                        (WIDTH - textW) / 2, startY + 8 + 32 + 4, c.textMuted(), false);
                }
            } else if (startY >= listEnd2) {
                // Squeezed out by the group block: no row is visible, so there is nothing
                // to scroll — keeping a stale totalH would be a dead scrollbar.
                newMaxScroll = 0;
            } else {
                newMaxScroll = Math.max(0, totalH - (listEnd2 - startY));
                // Clamp so a shrinking player list can't leave the view scrolled
                // into empty space until the next scroll input.
                int clampedOffset = Math.min(scrollOffset, newMaxScroll);

                g.enableScissor(0, startY, WIDTH, listEnd2);
                int scrollY = startY - clampedOffset;
                for (var info : players) {
                    String name = info.getProfile().getName();
                    if (name.equals(selfName)) continue;
                    if (ChatBubbleConfig.isSidebarHidden(name)) continue;
                    if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;

                    if (scrollY + ITEM_H > startY && scrollY < listEnd2) {
                        boolean sel = name.equals(whisperPartner);
                        boolean hoverRow = mouseX >= 0 && mouseX <= WIDTH
                            && mouseY >= scrollY && mouseY <= scrollY + ITEM_H;
                        if (sel)
                            com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_SELECTED), 0, scrollY, WIDTH, ITEM_H, alpha);
                        else if (hoverRow)
                            com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_HOVER), 0, scrollY, WIDTH, ITEM_H, alpha);

                        ResourceLocation skin = SkinResolver.getSkin(info.getProfile().getId(), name);
                        drawPlayerHead(g, skin, 4, scrollY + 3, 16, 18, alpha);

                        int tipW = ChatMessageStore.hasUnreadWhisper(name) ? 16 : 0;
                        int maxNameW = WIDTH - nameX - 4 - tipW - 2;
                        String displayName = font.plainSubstrByWidth(name, maxNameW - font.width("..."));
                        if (!displayName.equals(name)) displayName += "...";
                        g.drawString(font, Component.literal(displayName),
                            nameX, scrollY + 1, c.textPrimary(), false);

                        ChatMessageStore.ChatMessage latest = ChatMessageStore.getLatestWhisperWith(name);
                        if (latest != null) {
                            String preview = ChatMessageStore.singleLine(latest.content().getString());
                            String previewDisplay = font.plainSubstrByWidth(preview, maxNameW - font.width("..."));
                            if (!previewDisplay.equals(preview)) previewDisplay += "...";
                            g.drawString(font, Component.literal(previewDisplay),
                                nameX, scrollY + 1 + font.lineHeight, c.textMuted(), false);
                        }

                        if (ChatMessageStore.hasUnreadWhisper(name)) {
                            int tipX = WIDTH - 16 - 2;
                            double wave = Math.abs(Math.sin(System.currentTimeMillis() / 300.0)) * 3;
                            int tipY = scrollY + 3 + (int) wave;
                            drawIcon(g, privateTipIcon, tipX, tipY, 16, alpha);
                        }
                    }
                    scrollY += ITEM_H + 2;
                }
                g.disableScissor();
            }
        }

        // 「＋ 创建群组」: pinned into the sidebar's share of the bottom bar, on the same
        // line as the input row's icons (the column runs to the window bottom, so that is
        // the anchor — see render()). The group block above stops at the block's own bound.
        int btnY = SidebarModel.createButtonY(columnBottom);
        int btnX = SidebarModel.createButtonX();
        int btnW = SidebarModel.createButtonW();
        boolean hoverBtn = mouseX >= btnX && mouseX <= btnX + btnW
            && mouseY >= btnY && mouseY <= btnY + SidebarModel.CREATE_BTN_H;
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g,
            UiTextureManager.rl(hoverBtn ? UiElement.SIDEBAR_HOVER : UiElement.INPUT_BG),
            btnX, btnY, btnW, SidebarModel.CREATE_BTN_H, alpha);
        String create = Component.translatable("e33chat.sidebar.create_group").getString();
        g.drawString(font, Component.literal(create),
            SidebarModel.createButtonTextX(font.width(create)), btnY + 6, c.textPrimary(), false);
        return newMaxScroll;
    }

    // ---- Helpers ----

    private static void drawPlayerHead(GuiGraphics g, ResourceLocation skin, int x, int y,
                                       int baseSize, int hatSize, float alpha) {
        if (alpha <= 0.003f) return;
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, skin, x, y, baseSize, baseSize,
            8.0F, 8.0F, 8, 8, 64, 64, alpha);
        int hatOff = (hatSize - baseSize) / 2;
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, skin, x - hatOff, y - hatOff, hatSize, hatSize,
            40.0F, 8.0F, 8, 8, 64, 64, alpha);
    }

    private static void drawIcon(GuiGraphics g, ResourceLocation tex, int x, int y, int size, float alpha) {
        if (alpha <= 0.003f) return;
        if (size < 16) {
            // 同 ChatBars.drawIcon：采样内容区 14x14（偏移1,1）完整绘制，避免切掉图标右/下缘
            com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, tex, x, y, size, size,
                1f, 1f, 14, 14, 16, 16, alpha);
        } else {
            com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g, tex, x, y, size, size,
                0f, 0f, size, size, size, size, alpha);
        }
    }
}
