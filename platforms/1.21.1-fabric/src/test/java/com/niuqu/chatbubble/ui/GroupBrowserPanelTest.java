package com.niuqu.chatbubble.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two group-management defects:
 *
 * <p><b>A (alignment)</b> — the drilled-in group name used a bare {@code y + 1} while
 * that row's 邀请/重命名/解散 buttons centre their label, so the name sat ~3px high;
 * and the input-row 确认 used a 4px right margin while 返回 used 6px, so their right
 * edges never lined up. Both are pure integer geometry, pinned here.
 *
 * <p><b>B (rename)</b> — 重命名 used to fire RENAME immediately with whatever the (often
 * empty) field held, so the server answered {@code e33chat.group.bad_name}
 * ("字符错误"). The routing decision is now pure ({@link GroupBrowserPanel#decideConfirm})
 * and asserted directly: entering rename mode sends nothing, 确认 sends RENAME with the
 * typed name, and a blank name stays in edit mode instead of reaching the server.
 */
class GroupBrowserPanelTest {

    private static final int LINE_H = 9; // vanilla font line height

    // "Confirm" at 6px/char in the vanilla font → a plausible non-trivial button width.
    private static final int PANEL_W = 230;
    private static final int BUTTON_W = 6 * 7 + 8; // font.width("Confirm") + 8

    // ==== A1: the group name shares the button label's baseline ====

    @Test
    void groupNameSharesTheButtonBaseline() {
        int rowY = 50;
        int buttonBaseline = rowY + (GroupBrowserPanel.ROW_H - LINE_H) / 2 + 1; // drawSmall label
        assertEquals(buttonBaseline, GroupBrowserPanel.rowTextY(rowY, LINE_H),
            "the row name must sit on the same centre line as its buttons");
    }

    @Test
    void groupNameIsNoLongerThreePixelsHigh() {
        // Regression witness for the old `y + 1`: that produced a baseline 3px above
        // the shared one, which is exactly the "没水平对齐" the players reported.
        int rowY = 50;
        int oldBaseline = rowY + 1;
        assertEquals(3, GroupBrowserPanel.rowTextY(rowY, LINE_H) - oldBaseline,
            "the fix must move the name DOWN by 3px, not leave the old bare y+1");
    }

    // ==== A2: 返回 and 确认 share the same right edge ====

    @Test
    void backAndConfirmShareTheRightEdge() {
        int backW = 6 * 4 + 8; // font.width("Back") + 8
        int backRight = GroupBrowserPanel.backButtonX(100, PANEL_W, backW) + backW;
        int confirmRight = GroupBrowserPanel.inputButtonX(100, PANEL_W, BUTTON_W) + BUTTON_W;
        assertEquals(backRight, confirmRight, "the confirm button's right edge must match 返回");
    }

    @Test
    void confirmRightEdgeIsTheSharedMargin() {
        int px = 100;
        int confirmRight = GroupBrowserPanel.inputButtonX(px, PANEL_W, BUTTON_W) + BUTTON_W;
        assertEquals(px + PANEL_W - GroupBrowserPanel.SIDE_MARGIN, confirmRight,
            "right edge must be the shared 6px margin (the old 4px is the bug)");
    }

    @Test
    void inputBoxAndButtonKeepAPositiveGap() {
        // The input is 4px from the left edge and ends 12px before the button's left.
        int inputW = GroupBrowserPanel.inputWidth(PANEL_W, BUTTON_W);
        int inputRight = 4 + inputW;
        int buttonLeft = GroupBrowserPanel.inputButtonX(0, PANEL_W, BUTTON_W);
        assertEquals(12, buttonLeft - inputRight, "a sane gap stays between box and button");
        assertTrue(inputW > 0, "the input box must still have width");
    }

    // ==== B: the confirm / rename routing decision ====

    @Test
    void confirmOutsideRenameModeKeepsTheOldSemantics() {
        // Invite while drilled in, create in the list view — unchanged from before.
        assertEquals(GroupBrowserPanel.ACT_INVITE,
            GroupBrowserPanel.decideConfirm(false, true, "Alice").action());
        assertEquals(GroupBrowserPanel.ACT_CREATE,
            GroupBrowserPanel.decideConfirm(false, false, "NewGroup").action());
    }

    @Test
    void confirmInRenameModeSendsRenameWithTheTypedName() {
        var d = GroupBrowserPanel.decideConfirm(true, true, "新名字");
        assertEquals(GroupBrowserPanel.ACT_RENAME, d.action(),
            "确认 in rename mode must send RENAME, not INVITE");
        assertEquals("新名字", d.name(), "the typed name is the RENAME payload");
        assertFalse(d.renameMode(), "sending exits rename mode");
        assertTrue(d.clearField(), "the field is reset after the send");
    }

    @Test
    void confirmInRenameModeTrimsAndRejectsBlank() {
        // Padded name trims to a usable payload.
        assertEquals("Bob", GroupBrowserPanel.decideConfirm(true, true, "  Bob  ").name());

        // A blank name sends NOTHING and keeps editing — the original defect was
        // firing RENAME with "" and getting bad_name back.
        var blank = GroupBrowserPanel.decideConfirm(true, true, "   ");
        assertEquals(GroupBrowserPanel.ACT_NONE, blank.action(),
            "an empty rename must not be sent (the old code fired RENAME and got bad_name)");
        assertTrue(blank.renameMode(), "an empty rename stays in edit mode");
        assertFalse(blank.clearField(), "an empty rename keeps the field");

        assertEquals(GroupBrowserPanel.ACT_NONE, GroupBrowserPanel.decideConfirm(true, true, "").action());
        assertEquals(GroupBrowserPanel.ACT_NONE, GroupBrowserPanel.decideConfirm(true, true, null).action());
    }

    @Test
    void renameModeNeverSendsInviteOrCreate() {
        // Whatever the field holds, rename mode only ever routes to RENAME or nothing.
        for (String typed : new String[]{"", "  ", "x", "团队 alpha"}) {
            int action = GroupBrowserPanel.decideConfirm(true, true, typed).action();
            assertTrue(action == GroupBrowserPanel.ACT_RENAME || action == GroupBrowserPanel.ACT_NONE,
                "rename mode leaked a non-rename action for input " + typed);
        }
    }
}
