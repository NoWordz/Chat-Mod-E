package com.niuqu.chatbubble.render;

import com.niuqu.chatbubble.chat.GroupChannelState;
import com.niuqu.chatbubble.logic.SidebarModel;
import com.niuqu.chatbubble.store.ChatMessageStore.ChatMessage;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring test for the sidebar click route, not just the pure models it uses.
 *
 * <p>M5 shipped the group UI and wired {@code SidebarModel} / {@code GroupChannelState}
 * into the three screens, but left the wiring itself untested: the reviewer's
 * {@code 1.20.1} experiment set the 「世界频道」 branch back to the baseline
 * {@code whisperPartner = null} (B1 relapse — dead row, no tab change) and the whole
 * suite stayed green.
 *
 * <p>These tests go through the screen seams {@code isWorldRowHit} /
 * {@code handleSidebarWorldRowClick} / {@code isCreateButtonHit}, and assert on
 * {@code GroupChannelState} (the state machine behind them), so both halves of the
 * wiring have to agree: {@code handleSidebarWorldRowClick} must call
 * {@code GroupChannelState.selectWorldRow}, and that call must leave the group for
 * 全部 with system rows still reachable.
 */
class ChatBubbleScreenSidebarWiringTest {

    private static void withGroupsEnabled() {
        GroupChannelState.reset();
        GroupChannelState.enabled = true;
    }

    private static ChatMessage systemRow() {
        return new ChatMessage(UUID.randomUUID(), Component.literal("Server"), Component.literal("x"),
            0L, false, true, null, null, null, 0, "Server", false, null, null,
            null, null);
    }

    // ==== B1: the 「世界频道」 row must leave a group for 全部 ====

    @Test
    void worldRowBandIsTheOneRowUnderTheSearchBox() {
        assertTrue(ChatBubbleScreen.isWorldRowHit(SidebarModel.PUBLIC_Y), "top edge");
        assertTrue(ChatBubbleScreen.isWorldRowHit(SidebarModel.PUBLIC_Y + SidebarModel.ROW_H), "bottom edge, inclusive");
        assertFalse(ChatBubbleScreen.isWorldRowHit(SidebarModel.PUBLIC_Y - 1), "above the row");
        assertFalse(ChatBubbleScreen.isWorldRowHit(SidebarModel.PUBLIC_Y + SidebarModel.ROW_H + 1), "below the row");
    }

    /**
     * The headline B1 regression: clicking the row while inside a group must select
     * 全部 (leaving the group) — never stay on the group tab, and never switch to
     * 世界 (whose filter drops system rows).
     */
    @Test
    void worldRowClickLeavesGroupForAll() {
        withGroupsEnabled();
        GroupChannelState.setActive("11111111-1111-1111-1111-111111111111");
        assertTrue(GroupChannelState.isGroup(), "precondition: the group tab is active");

        assertTrue(ChatBubbleScreen.handleSidebarWorldRowClick(SidebarModel.PUBLIC_Y),
            "the click landed on the row");
        assertEquals(GroupChannelState.TAB_ALL, GroupChannelState.active(),
            "B1: leaving a group must select 全部, not stay on the dead group row");
        assertFalse(GroupChannelState.isGroup(), "the group tab must be gone");
    }

    /** After the fix, a public system row is visible again — the "dead row" symptom. */
    @Test
    void worldRowClickLeavesSystemMessagesReachable() {
        withGroupsEnabled();
        GroupChannelState.setActive("11111111-1111-1111-1111-111111111111");
        ChatBubbleScreen.handleSidebarWorldRowClick(SidebarModel.PUBLIC_Y);

        assertEquals(1, GroupChannelState.filterMessages(List.of(systemRow()),
            GroupChannelState.active()).size(),
            "system rows must be reachable right after leaving the group");
    }

    /** The bug's second half: the click must also end a whisper view. */
    @Test
    void worldRowClickEndsWhisperView() {
        withGroupsEnabled();
        GroupChannelState.selectWhisper("alice");
        assertTrue(GroupChannelState.isWhisper(), "precondition: whisper view active");

        assertTrue(ChatBubbleScreen.handleSidebarWorldRowClick(SidebarModel.PUBLIC_Y));
        assertFalse(GroupChannelState.isWhisper(), "the world row ends the whisper view");
    }

    /** 2.4.17 parity: on a pseudo tab the row is a no-op. */
    @Test
    void worldRowClickOnAPseudoTabChangesNothing() {
        withGroupsEnabled();
        GroupChannelState.setActive(GroupChannelState.TAB_SYSTEM);

        assertTrue(ChatBubbleScreen.handleSidebarWorldRowClick(SidebarModel.PUBLIC_Y));
        assertEquals(GroupChannelState.TAB_SYSTEM, GroupChannelState.active());
    }

    /** Outside the band the screen must not touch the channel state. */
    @Test
    void worldRowClickOutsideTheBandIsIgnored() {
        withGroupsEnabled();
        GroupChannelState.setActive("11111111-1111-1111-1111-111111111111");

        assertFalse(ChatBubbleScreen.handleSidebarWorldRowClick(SidebarModel.PUBLIC_Y - 1));
        assertTrue(GroupChannelState.isGroup(), "a miss must not leave the group");
    }

    // ==== group rows must not shadow the pinned create button ====

    @Test
    void groupRowFittingAboveTheListBottomIsClickable() {
        int listEnd = SidebarModel.listBottom(320);
        int gy = SidebarModel.groupWindowRowY(20, 20, 16); // scrolled to the tail
        assertTrue(ChatBubbleScreen.isGroupRowVisible(gy, listEnd));
        assertTrue(ChatBubbleScreen.isGroupRowHit(gy, gy));
        assertTrue(ChatBubbleScreen.isGroupRowHit(gy + SidebarModel.ROW_H, gy), "inclusive bottom edge");
    }

    @Test
    void groupRowCrossingTheListBottomIsNotClickable() {
        int listEnd = SidebarModel.listBottom(320);
        assertFalse(ChatBubbleScreen.isGroupRowVisible(listEnd - SidebarModel.ROW_H + 1, listEnd),
            "the half-row that the draw clips can never be a hit");
        assertTrue(ChatBubbleScreen.isGroupRowVisible(listEnd - SidebarModel.ROW_H, listEnd));
    }

    // ==== create button band ====

    @Test
    void createButtonHitIsItsOwnBandAndSideInset() {
        int columnBottom = 320;
        int top = SidebarModel.createButtonY(columnBottom);
        int left = SidebarModel.createButtonX();
        int right = left + SidebarModel.createButtonW();
        int midX = (left + right) / 2;
        assertTrue(ChatBubbleScreen.isCreateButtonHit(top, midX, columnBottom), "top edge");
        assertTrue(ChatBubbleScreen.isCreateButtonHit(
            top + SidebarModel.CREATE_BTN_H, midX, columnBottom), "bottom edge");
        assertFalse(ChatBubbleScreen.isCreateButtonHit(top - 1, midX, columnBottom), "above the button");
        // The hit band is the drawn frame from createButtonX/createButtonW — an old
        // hard-coded `4`/`WIDTH-8` draw would no longer line up with these edges.
        assertTrue(ChatBubbleScreen.isCreateButtonHit(top, left, columnBottom), "left frame edge");
        assertTrue(ChatBubbleScreen.isCreateButtonHit(top, right, columnBottom), "right frame edge");
        assertFalse(ChatBubbleScreen.isCreateButtonHit(top, left - 1, columnBottom), "left of the inset");
        assertFalse(ChatBubbleScreen.isCreateButtonHit(top, right + 1, columnBottom), "right of the inset");
    }

    /**
     * The button is centred in the bottom bar using the model's own {@code BOTTOM_BAR_H}.
     * That constant must track the real {@code ChatLayout.BAR_H}; if the bar is ever
     * resized and only one of the two moves, the button silently leaves the icon row.
     */
    @Test
    void bottomBarHeightIsBoundToTheRealBar() {
        assertEquals(ChatLayout.BAR_H, SidebarModel.BOTTOM_BAR_H,
            "SidebarModel.BOTTOM_BAR_H must mirror ChatLayout.BAR_H");
        int columnBottom = 320;
        int btnBottom = SidebarModel.createButtonY(columnBottom) + SidebarModel.CREATE_BTN_H;
        assertEquals(columnBottom - ChatLayout.BAR_H / 2 - SidebarModel.CREATE_BTN_H / 2, SidebarModel.createButtonY(columnBottom),
            "the button top sits half the bar's slack below the bar top");
        assertEquals(columnBottom - SidebarModel.CREATE_BOTTOM_INSET, btnBottom,
            "and half the slack above the window bottom");
    }
}
