package com.niuqu.chatbubble.ui;

import com.niuqu.chatbubble.chat.GroupChannelState;
import com.niuqu.chatbubble.logic.PanelHitTable;
import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.texture.ColoredTextureRenderer;
import com.niuqu.chatbubble.texture.UiElement;
import com.niuqu.chatbubble.texture.UiTextureManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Group management popup (2.5.0, invite-based). Two views:
 *
 * <ul>
 *   <li><b>list</b> — the invitations the server sent (accept / reject) and the
 *       groups this player belongs to (click a row to manage it), plus the create
 *       box. There is no public "join": membership only starts from an accepted
 *       invitation.</li>
 *   <li><b>manage</b> — the drilled-in group: invite (name input), rename (name
 *       input), disband, and a member list where each member can be kicked or the
 *       ownership transferred to them. Leaving is in the list view.</li>
 * </ul>
 *
 * The panel only paints and hit-tests; it returns an encoded ACT_* plus the ids
 * the action targets, and the screen owns the packet dispatch. The server always
 * re-authorises, so these buttons are presentation.
 */
public class GroupBrowserPanel {
    public static final int ACT_NONE = 0;
    public static final int ACT_CREATE = 103;
    public static final int ACT_ACCEPT = 110;
    public static final int ACT_REJECT = 111;
    public static final int ACT_LEAVE = 112;
    public static final int ACT_RENAME = 113;
    public static final int ACT_INVITE = 114;
    public static final int ACT_KICK = 115;
    public static final int ACT_TRANSFER = 116;
    public static final int ACT_DISBAND = 117;
    /** Open a pre-2.5 name-keyed group channel (no stable id; {@code actionGroup} is the name). */
    public static final int ACT_OPEN = 118;

    static final int PANEL_W = 230;
    static final int ROW_H = 16;
    static final int INPUT_H = 14;
    static final int TITLE_H = 16;
    static final int MAX_ROWS = 6;

    public boolean visible;

    /** Name typed into the input for create / invite / rename. */
    public String actionGroup;
    /** Group the returned action targets. */
    public UUID actionGroupId;
    /** Kick / transfer target member. */
    public UUID actionSubject;
    /** Accept / reject invitation id. */
    public UUID actionInvitation;

    /** Drilled-in group, or null for the list view. */
    private UUID selected;

    private int px, py, w, h;

    private final List<int[]> inviteAcceptRects = new ArrayList<>();
    private final List<UUID> inviteAcceptIds = new ArrayList<>();
    private final List<int[]> inviteRejectRects = new ArrayList<>();
    private final List<UUID> inviteRejectIds = new ArrayList<>();
    private final List<int[]> groupRowRects = new ArrayList<>();
    private final List<UUID> groupRowIds = new ArrayList<>();
    private final List<int[]> legacyRowRects = new ArrayList<>();
    private final List<String> legacyRowNames = new ArrayList<>();
    private final List<int[]> leaveBtnRects = new ArrayList<>();
    private final List<UUID> leaveBtnIds = new ArrayList<>();
    private final List<int[]> kickBtnRects = new ArrayList<>();
    private final List<UUID> kickBtnIds = new ArrayList<>();
    private final List<int[]> transferBtnRects = new ArrayList<>();
    private final List<UUID> transferBtnIds = new ArrayList<>();
    private int[] createBtn;
    private int[] backBtn;
    private int[] renameBtn;
    private int[] inviteBtn;
    private int[] disbandBtn;

    /**
     * Ordered rect->action table for this frame. The order is the contract: it is
     * built to match the old hand-written precedence (back, invite accept/reject,
     * leave, rows, then the manage buttons, then create). One shared
     * {@link PanelHitTable} replaces the per-kind {@code over()} scans, so the
     * three targets resolve a panel click identically.
     */
    private final PanelHitTable hitTable = new PanelHitTable();

    private void clearHitRects() {
        inviteAcceptRects.clear(); inviteAcceptIds.clear();
        inviteRejectRects.clear(); inviteRejectIds.clear();
        groupRowRects.clear(); groupRowIds.clear();
        legacyRowRects.clear(); legacyRowNames.clear();
        leaveBtnRects.clear(); leaveBtnIds.clear();
        kickBtnRects.clear(); kickBtnIds.clear();
        transferBtnRects.clear(); transferBtnIds.clear();
        createBtn = null; backBtn = null; renameBtn = null; inviteBtn = null; disbandBtn = null;
    }

    private int computedHeight() {
        int rows = GroupChannelState.invitations().size() + GroupChannelState.directory().size()
            + GroupChannelState.legacyTabs().size();
        rows = Math.min(rows, MAX_ROWS + 1);
        int memberRows = selected == null ? 0 : Math.min(selectedMembers().size(), MAX_ROWS);
        return TITLE_H + 4 + (rows + memberRows) * (ROW_H + 2) + 4 + INPUT_H + 6;
    }

    private List<UUID> selectedMembers() {
        if (selected == null) return List.of();
        GroupDirectoryEntry entry = GroupChannelState.directoryEntry(selected);
        if (entry == null) return List.of();
        List<UUID> out = new ArrayList<>();
        for (UUID id : entry.memberIds()) {
            if (id != null && !id.equals(entry.ownerId())) out.add(id);
        }
        return out;
    }

    private List<String> memberNames() {
        List<UUID> ids = selectedMembers();
        List<String> names = new ArrayList<>(ids.size());
        var seen = ChatMessageStore.seenPlayerSnapshot();
        for (UUID id : ids) {
            String name = null;
            for (var sp : seen) {
                if (sp.uuid().equals(id)) { name = sp.displayName() != null ? sp.displayName() : sp.profileName(); break; }
            }
            names.add(name != null && !name.isEmpty() ? name : shortId(id));
        }
        return names;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public void render(DrawContext g, int mouseX, int mouseY, TextRenderer font, ChatBubbleTheme.Colors c,
                       int panelX, int panelW, int barTop, TextFieldWidget createInput, float alpha) {
        if (!visible) return;
        int a255 = (int) (255 * alpha);
        w = Math.max(120, Math.min(PANEL_W, panelW - 4));
        h = computedHeight();
        px = ChatSearchPanel.clampX(panelX + panelW / 2 - w / 2, w, panelX, panelW);
        py = barTop - h - 4;
        clearHitRects();

        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.CONTENT_BG),
            px, py, w, h, alpha);
        g.drawBorder(px, py, w, h, ChatBubbleTheme.alphaBlend(c.divider(), a255));

        int y = py + 3;
        g.drawText(font, Text.translatable(selected == null
                ? "e33chat.group.browser_title" : "e33chat.group.manage_title").getString(),
            px + 6, y, ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
        if (selected != null) {
            String back = Text.translatable("e33chat.group.back_btn").getString();
            int bw = font.getWidth(back) + 8;
            int bx = px + w - bw - 6;
            drawButton(g, font, c, back, bx, y - 1, bw, a255, mouseX, mouseY);
            backBtn = new int[]{bx, y - 1, bx + bw, y - 1 + TITLE_H - 2};
        }
        y += TITLE_H;

        if (selected == null) {
            renderList(g, font, c, mouseX, mouseY, a255, y, createInput, alpha);
        } else {
            renderManage(g, font, c, mouseX, mouseY, a255, y, createInput, alpha);
        }
           rebuildHitTable();
    }

    // Hit action code bases; the low bits carry the entry index.
    private static final int HIT_BACK = 1;
    private static final int HIT_INVITE = 2;
    private static final int HIT_RENAME = 3;
    private static final int HIT_DISBAND = 4;
    private static final int HIT_CREATE = 5;
    private static final int HIT_ACCEPT_BASE = 1000;
    private static final int HIT_REJECT_BASE = 2000;
    private static final int HIT_LEAVE_BASE = 3000;
    private static final int HIT_GROUP_BASE = 4000;
    private static final int HIT_LEGACY_BASE = 5000;
    private static final int HIT_KICK_BASE = 6000;
    private static final int HIT_TRANSFER_BASE = 7000;

    /** Rebuild the ordered hit table from the rects this frame's render recorded. */
    private void rebuildHitTable() {
        hitTable.clear();
        if (backBtn != null) hitTable.add(backBtn[0], backBtn[1], backBtn[2], backBtn[3], HIT_BACK);
        for (int i = 0; i < inviteAcceptRects.size(); i++) {
            int[] r = inviteAcceptRects.get(i);
            hitTable.add(r[0], r[1], r[2], r[3], HIT_ACCEPT_BASE + i);
            int[] rr = inviteRejectRects.get(i);
            hitTable.add(rr[0], rr[1], rr[2], rr[3], HIT_REJECT_BASE + i);
        }
        if (selected == null) {
            for (int i = 0; i < leaveBtnRects.size(); i++) {
                int[] r = leaveBtnRects.get(i);
                hitTable.add(r[0], r[1], r[2], r[3], HIT_LEAVE_BASE + i);
            }
            for (int i = 0; i < groupRowRects.size(); i++) {
                int[] r = groupRowRects.get(i);
                hitTable.add(r[0], r[1], r[2], r[3], HIT_GROUP_BASE + i);
            }
            for (int i = 0; i < legacyRowRects.size(); i++) {
                int[] r = legacyRowRects.get(i);
                hitTable.add(r[0], r[1], r[2], r[3], HIT_LEGACY_BASE + i);
            }
        } else {
            if (inviteBtn != null) hitTable.add(inviteBtn[0], inviteBtn[1], inviteBtn[2], inviteBtn[3], HIT_INVITE);
            if (renameBtn != null) hitTable.add(renameBtn[0], renameBtn[1], renameBtn[2], renameBtn[3], HIT_RENAME);
            if (disbandBtn != null) hitTable.add(disbandBtn[0], disbandBtn[1], disbandBtn[2], disbandBtn[3], HIT_DISBAND);
            for (int i = 0; i < kickBtnRects.size(); i++) {
                int[] r = kickBtnRects.get(i);
                hitTable.add(r[0], r[1], r[2], r[3], HIT_KICK_BASE + i);
            }
            for (int i = 0; i < transferBtnRects.size(); i++) {
                int[] r = transferBtnRects.get(i);
                hitTable.add(r[0], r[1], r[2], r[3], HIT_TRANSFER_BASE + i);
            }
        }
        if (createBtn != null) hitTable.add(createBtn[0], createBtn[1], createBtn[2], createBtn[3], HIT_CREATE);
    }

    private void renderList(DrawContext g, TextRenderer font, ChatBubbleTheme.Colors c,
                            int mouseX, int mouseY, int a255, int y, TextFieldWidget createInput, float alpha) {
        for (GroupInvitationSummary invite : GroupChannelState.invitations()) {
            String label = Text.translatable("e33chat.group.invite_row", invite.groupName()).getString();
            g.drawText(font, label, px + 6, y + (ROW_H - font.fontHeight) / 2 + 1,
                ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
            String ok = Text.translatable("e33chat.group.accept_btn").getString();
            String no = Text.translatable("e33chat.group.reject_btn").getString();
            int noW = font.getWidth(no) + 8;
            int okW = font.getWidth(ok) + 8;
            int nx = px + w - noW - 6;
            int ox = nx - okW - 3;
            drawButton(g, font, c, ok, ox, y + 1, okW, a255, mouseX, mouseY);
            drawButton(g, font, c, no, nx, y + 1, noW, a255, mouseX, mouseY);
            inviteAcceptRects.add(new int[]{ox, y + 1, ox + okW, y + ROW_H - 1});
            inviteAcceptIds.add(invite.id());
            inviteRejectRects.add(new int[]{nx, y + 1, nx + noW, y + ROW_H - 1});
            inviteRejectIds.add(invite.id());
            y += ROW_H + 2;
        }

        if (!GroupChannelState.invitations().isEmpty()) {
            g.drawText(font, Text.translatable("e33chat.group.my_groups_header").getString(),
                px + 6, y + 1, ChatBubbleTheme.alphaBlend(c.textMuted(), a255), false);
            y += ROW_H + 2;
        }

        int shown = 0;
        for (GroupDirectoryEntry entry : GroupChannelState.directory().values()) {
            if (shown >= MAX_ROWS) break;
            shown++;
            UUID id = entry.identity().id();
            String name = entry.identity().displayName();
            boolean hover = mouseX >= px + 3 && mouseX <= px + w - 3 && mouseY >= y && mouseY <= y + ROW_H;
            if (hover) g.fill(px + 3, y, px + w - 3, y + ROW_H,
                ChatBubbleTheme.alphaBlend(c.sidebarItemHover(), a255));
            g.drawText(font, name, px + 6, y + (ROW_H - font.fontHeight) / 2 + 1,
                ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
            groupRowRects.add(new int[]{px + 3, y, px + w - 3, y + ROW_H});
            groupRowIds.add(id);

            String leave = Text.translatable("e33chat.group.leave_btn").getString();
            int bw = font.getWidth(leave) + 8;
            int bx = px + w - bw - 6;
            drawButton(g, font, c, leave, bx, y + 1, bw, a255, mouseX, mouseY);
            leaveBtnRects.add(new int[]{bx, y + 1, bx + bw, y + ROW_H - 1});
            leaveBtnIds.add(id);
            y += ROW_H + 2;
        }

        // Pre-2.5 groups (no stable id): clicking the row opens the name-keyed tab.
        for (String name : GroupChannelState.legacyTabs()) {
            if (shown >= MAX_ROWS) break;
            shown++;
            boolean hover = mouseX >= px + 3 && mouseX <= px + w - 3 && mouseY >= y && mouseY <= y + ROW_H;
            if (hover) g.fill(px + 3, y, px + w - 3, y + ROW_H,
                ChatBubbleTheme.alphaBlend(c.sidebarItemHover(), a255));
            g.drawText(font, name, px + 6, y + (ROW_H - font.fontHeight) / 2 + 1,
                ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
            legacyRowRects.add(new int[]{px + 3, y, px + w - 3, y + ROW_H});
            legacyRowNames.add(name);
            y += ROW_H + 2;
        }

        if (GroupChannelState.directory().size() > MAX_ROWS) {
            g.drawText(font, Text.translatable("e33chat.group.more_hint",
                    GroupChannelState.directory().size() - MAX_ROWS).getString(),
                px + 6, y + 1, ChatBubbleTheme.alphaBlend(c.textMuted(), a255), false);
            y += ROW_H + 2;
        }

        y += 4;
        renderInputRow(g, font, c, mouseX, mouseY, a255, y, createInput, alpha,
            Text.translatable("e33chat.group.create_placeholder").getString(),
            Text.translatable("e33chat.group.create_btn").getString());
    }

    private void renderManage(DrawContext g, TextRenderer font, ChatBubbleTheme.Colors c,
                              int mouseX, int mouseY, int a255, int y, TextFieldWidget createInput, float alpha) {
        GroupDirectoryEntry entry = GroupChannelState.directoryEntry(selected);
        String name = entry != null ? entry.identity().displayName() : "";
        g.drawText(font, name, px + 6, y + 1, ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);

        boolean owner = entry != null && entry.ownerId() != null && entry.ownerId().equals(localPlayerId());
        String invite = Text.translatable("e33chat.group.invite_btn").getString();
        String rename = Text.translatable("e33chat.group.rename_btn").getString();
        String disband = Text.translatable("e33chat.group.disband_btn").getString();
        int bx = px + 6 + font.getWidth(name) + 8;
        int avail = px + w - 6 - bx;
        if (owner && font.getWidth(invite) + font.getWidth(rename) + font.getWidth(disband) + 30 <= avail) {
            inviteBtn = drawSmall(g, font, c, invite, bx, y, a255, mouseX, mouseY); bx += inviteBtn[2] - inviteBtn[0] + 3;
            renameBtn = drawSmall(g, font, c, rename, bx, y, a255, mouseX, mouseY); bx += renameBtn[2] - renameBtn[0] + 3;
            disbandBtn = drawSmall(g, font, c, disband, bx, y, a255, mouseX, mouseY);
        } else {
            inviteBtn = drawSmall(g, font, c, invite, bx, y, a255, mouseX, mouseY);
        }
        y += ROW_H + 2;

        List<UUID> memberIds = selectedMembers();
        List<String> names = memberNames();
        int shown = 0;
        for (int i = 0; i < memberIds.size() && shown < MAX_ROWS; i++, shown++) {
            String memberName = names.get(i);
            g.drawText(font, memberName, px + 6, y + (ROW_H - font.fontHeight) / 2 + 1,
                ChatBubbleTheme.alphaBlend(c.textSecondary(), a255), false);
            String kick = Text.translatable("e33chat.group.kick_btn").getString();
            String transfer = Text.translatable("e33chat.group.transfer_btn").getString();
            if (owner) {
                int tw = font.getWidth(transfer) + 8;
                int kw = font.getWidth(kick) + 8;
                int tx = px + w - tw - 6;
                int kx = tx - kw - 3;
                int[] kr = drawSmall(g, font, c, kick, kx, y, a255, mouseX, mouseY);
                int[] tr = drawSmall(g, font, c, transfer, tx, y, a255, mouseX, mouseY);
                kickBtnRects.add(kr); kickBtnIds.add(memberIds.get(i));
                transferBtnRects.add(tr); transferBtnIds.add(memberIds.get(i));
            } else {
                String hint = Text.translatable("e33chat.group.member_hint").getString();
                g.drawText(font, hint, px + w - 6 - font.getWidth(hint),
                    y + (ROW_H - font.fontHeight) / 2 + 1,
                    ChatBubbleTheme.alphaBlend(c.textMuted(), a255), false);
            }
            y += ROW_H + 2;
        }

        y += 4;
        renderInputRow(g, font, c, mouseX, mouseY, a255, y, createInput, alpha,
            Text.translatable("e33chat.group.name_placeholder").getString(),
            Text.translatable("e33chat.group.confirm_btn").getString());
    }

    private void renderInputRow(DrawContext g, TextRenderer font, ChatBubbleTheme.Colors c,
                                int mouseX, int mouseY, int a255, int y, TextFieldWidget createInput, float alpha,
                                String placeholder, String button) {
        int inputW = w - 8 - (font.getWidth(button) + 12);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.INPUT_BG),
            px + 4, y, inputW, INPUT_H, alpha);
        boolean inputHover = mouseX >= px + 4 && mouseX <= px + 4 + inputW
            && mouseY >= y && mouseY <= y + INPUT_H;
        if (inputHover || createInput.isFocused())
            g.drawBorder(px + 4, y, inputW, INPUT_H, ChatBubbleTheme.alphaBlend(c.textMuted(), a255));
        createInput.setX(px + 6);
        createInput.setY(y + 3);
        createInput.setWidth(inputW - 4);
        createInput.setHeight(INPUT_H - 2);
        createInput.setVisible(true);
        if (createInput.getText().isEmpty()) {
            g.drawText(font, placeholder, px + 6, y + 3,
                ChatBubbleTheme.alphaBlend(c.textMuted(), a255), false);
        }
        int bw = font.getWidth(button) + 8;
        int bx = px + w - bw - 4;
        drawButton(g, font, c, button, bx, y, bw, a255, mouseX, mouseY);
        createBtn = new int[]{bx, y, bx + bw, y + INPUT_H};
    }

    private void drawButton(DrawContext g, TextRenderer font, ChatBubbleTheme.Colors c, String label,
                            int x, int y, int bw, int a255, int mouseX, int mouseY) {
        boolean hov = mouseX >= x && mouseX <= x + bw && mouseY >= y && mouseY <= y + ROW_H - 1;
        g.fill(x, y, x + bw, y + ROW_H - 1,
            ChatBubbleTheme.alphaBlend(hov ? c.contextHover() : c.popupBg(), a255));
        g.drawBorder(x, y, bw, ROW_H - 1, ChatBubbleTheme.alphaBlend(c.divider(), a255));
        g.drawText(font, label, x + 4, y + (ROW_H - 2 - font.fontHeight) / 2 + 1,
            ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
    }

    private int[] drawSmall(DrawContext g, TextRenderer font, ChatBubbleTheme.Colors c, String label,
                            int x, int y, int a255, int mouseX, int mouseY) {
        int bw = font.getWidth(label) + 8;
        boolean hov = mouseX >= x && mouseX <= x + bw && mouseY >= y + 1 && mouseY <= y + ROW_H - 1;
        g.fill(x, y + 1, x + bw, y + ROW_H - 1,
            ChatBubbleTheme.alphaBlend(hov ? c.contextHover() : c.popupBg(), a255));
        g.drawText(font, label, x + 4, y + (ROW_H - font.fontHeight) / 2 + 1,
            ChatBubbleTheme.alphaBlend(c.textSecondary(), a255), false);
        return new int[]{x, y + 1, x + bw, y + ROW_H - 1};
    }

    private static UUID localPlayerId() {
        var player = net.minecraft.client.MinecraftClient.getInstance().player;
        return player != null ? player.getUuid() : null;
    }

    /** Encoded action, or ACT_NONE. Clicks inside the panel are consumed (returns -1). */
    public int handleClick(double mx, double my, TextRenderer font,
                           int panelX, int panelW, int barTop, TextFieldWidget createInput) {
        if (!visible) return ACT_NONE;
        if (mx < px || mx > px + w || my < py || my > py + h) return ACT_NONE;

        // The ordered table is the frame's draw/hit contract; an unclaimed point
        // inside the panel is consumed (returns -1), exactly like the old scans.
        int code = hitTable.hit((int) mx, (int) my);
        if (code == PanelHitTable.NONE) return -1;

        if (code == HIT_BACK) { selected = null; return -1; }

        if (code >= HIT_ACCEPT_BASE && code < HIT_ACCEPT_BASE + inviteAcceptIds.size()) {
            actionInvitation = inviteAcceptIds.get(code - HIT_ACCEPT_BASE);
            actionGroupId = null;
            return ACT_ACCEPT;
        }
        if (code >= HIT_REJECT_BASE && code < HIT_REJECT_BASE + inviteRejectIds.size()) {
            actionInvitation = inviteRejectIds.get(code - HIT_REJECT_BASE);
            actionGroupId = null;
            return ACT_REJECT;
        }

        if (selected == null) {
            if (code >= HIT_LEAVE_BASE && code < HIT_LEAVE_BASE + leaveBtnIds.size()) {
                actionGroupId = leaveBtnIds.get(code - HIT_LEAVE_BASE);
                return ACT_LEAVE;
            }
            if (code >= HIT_GROUP_BASE && code < HIT_GROUP_BASE + groupRowIds.size()) {
                selected = groupRowIds.get(code - HIT_GROUP_BASE);
                // The manage view's Enter/confirm paths route on actionGroupId, so bind it
                // here too — otherwise Enter sent CREATE instead of INVITE.
                actionGroupId = selected;
                // Keep whatever the player typed: from here the same field is the
                // invitee/rename target, and wiping it made both buttons no-ops.
                return -1; // drilled in; the next click acts
            }
            if (code >= HIT_LEGACY_BASE && code < HIT_LEGACY_BASE + legacyRowNames.size()) {
                actionGroup = legacyRowNames.get(code - HIT_LEGACY_BASE);
                actionGroupId = null;
                return ACT_OPEN;
            }
        } else {
            actionGroupId = selected;
            // An empty field is no longer swallowed here: the request goes out and the server
            // answers with e33chat.group.no_target / .bad_name / .player_unknown
            // (GroupRules.java:276-279), which is the only feedback the player gets.
            if (code == HIT_INVITE) {
                actionGroup = createInput.getText().trim();
                createInput.setText("");
                return ACT_INVITE;
            }
            if (code == HIT_RENAME) {
                actionGroup = createInput.getText().trim();
                createInput.setText("");
                return ACT_RENAME;
            }
            if (code == HIT_DISBAND) return ACT_DISBAND;
            if (code >= HIT_KICK_BASE && code < HIT_KICK_BASE + kickBtnIds.size()) {
                actionSubject = kickBtnIds.get(code - HIT_KICK_BASE);
                return ACT_KICK;
            }
            if (code >= HIT_TRANSFER_BASE && code < HIT_TRANSFER_BASE + transferBtnIds.size()) {
                actionSubject = transferBtnIds.get(code - HIT_TRANSFER_BASE);
                return ACT_TRANSFER;
            }
        }

        if (code == HIT_CREATE) {
            // Empty goes through so the server's own refusal is spoken (bad_name / no_target)
            // instead of the click vanishing here.
            actionGroup = createInput.getText().trim();
            createInput.setText("");
            return selected == null ? ACT_CREATE : ACT_INVITE;
        }
        return -1;
    }

    public boolean isClickOnPanel(double mx, double my) {
        return visible && mx >= px && mx <= px + w && my >= py && my <= py + h;
    }

    /** True when a group is drilled in (the shared input then means invite, not create). */
    public boolean isManaging() {
        return selected != null;
    }

    public void reset() {
        selected = null;
        actionGroup = null;
        actionGroupId = null;
        actionSubject = null;
        actionInvitation = null;
    }

    private static boolean over(double mx, double my, int[] r) {
        return mx >= r[0] && mx <= r[2] && my >= r[1] && my <= r[3];
    }
}
