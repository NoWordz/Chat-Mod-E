package com.niuqu.chatbubble.logic;

import com.niuqu.chatbubble.render.UiLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sidebar channel column geometry and the click routing that the three
 * screens currently duplicate inline. Every assertion here corresponds to a real
 * draw/hit site, so a regression in the model turns this red.
 *
 * Geometry facts pinned against the live code (baseline 26e1e92e):
 *  - row height / step 22 / 24  -> ChatSidebar.java:22 ITEM_H, row advance ITEM_H+2
 *  - public row top 19          -> 2 + SEARCH_H(14) + 3
 *  - list bottom 293 / button 297 for a 320px-tall column -> SidebarModel.createButtonY/listBottom
 */
class SidebarModelTest {

    // ---- geometry ----

    @Test void publicRowSitsUnderTheSearchBox() {
        assertEquals(19, SidebarModel.PUBLIC_Y, "2 + SEARCH_H(14) + 3");
    }

    @Test void groupTitleIsAbsentWithoutGroups() {
        assertEquals(-1, SidebarModel.groupTitleY(0));
    }

    @Test void groupTitleAndFirstRowFollowTheBlockHeader() {
        // 19 + STEP(24) + GAP(2) = 45 caption, + TITLE_H(12) = 57 first row
        assertEquals(45, SidebarModel.groupTitleY(3));
        assertEquals(57, SidebarModel.groupRowsTop(3));
        assertEquals(57, SidebarModel.groupRowY(3, 0));
        assertEquals(81, SidebarModel.groupRowY(3, 1));
        assertEquals(105, SidebarModel.groupRowY(3, 2));
    }

    @Test void groupWindowShowsAtMostFiveRows() {
        assertEquals(3, SidebarModel.groupVisibleRows(3));
        assertEquals(5, SidebarModel.groupVisibleRows(9));
        // 9 groups -> 4 scrolled past -> 4 * 24
        assertEquals(96, SidebarModel.groupMaxScroll(9));
    }

    @Test void groupScrollIsRowGranular() {
        assertEquals(0, SidebarModel.groupFirstRow(9, 0));
        assertEquals(1, SidebarModel.groupFirstRow(9, 24));
        assertEquals(4, SidebarModel.groupFirstRow(9, 1000), "clamped to max first row");
        assertEquals(5, SidebarModel.groupWindowEnd(9, 0));
        assertEquals(9, SidebarModel.groupWindowEnd(9, 4));
        // scrolling swaps the visible range instead of sliding rows down over the button
        assertEquals(57, SidebarModel.groupWindowRowY(9, 4, 4));
        assertEquals(105, SidebarModel.groupWindowRowY(9, 6, 4));
    }

    @Test void playerListStartsAfterTheCappedGroupBlockAndButtonSlot() {
        // 57 (block) + 24 (create slot) = 81
        assertEquals(81, SidebarModel.playerStartY(0));
        // 57 + 1*24 = 81 (block) + 24 = 105
        assertEquals(105, SidebarModel.playerStartY(1));
        // capped at 5 visible rows: 57 + 5*24 = 177 + 24 = 201, no matter how many groups
        assertEquals(201, SidebarModel.playerStartY(9));
        assertEquals(201, SidebarModel.playerStartY(50));
    }

    @Test void groupBlockNeverReachesThePinnedCreateButton() {
        // the button is pinned to the column bottom; the capped block must stay above it
        int listEnd = SidebarModel.listBottom(320);
        assertTrue(SidebarModel.playerStartY(50) < listEnd,
            "50 groups must not push the player list into/past the button strip");
        assertEquals(293, listEnd);
        assertEquals(297, SidebarModel.createButtonY(320));
    }

    // ---- create button frame ----

    @Test void createButtonFrameIsSymmetricallyInset() {
        int left = SidebarModel.createButtonX();
        int right = SidebarModel.WIDTH - (SidebarModel.createButtonX() + SidebarModel.createButtonW());
        assertEquals(SidebarModel.CREATE_INSET, left, "left inset");
        assertEquals(SidebarModel.CREATE_INSET, right, "right inset must equal the left one");
        assertEquals(left, right, "the 2.4.0 edge-flush/1px-right look is exactly what this pins against");
    }

    @Test void createButtonWidthLeavesRoomOnBothSides() {
        assertEquals(SidebarModel.WIDTH - 2 * SidebarModel.CREATE_INSET, SidebarModel.createButtonW());
        // 6px inset -> a 78px-wide frame in the 90px column, not a 82px edge-to-edge slab
        assertEquals(6, SidebarModel.CREATE_INSET);
        assertEquals(78, SidebarModel.createButtonW());
    }

    @Test void createButtonTextCentreSitsOnTheFrameCentre() {
        int frameCentre2 = 2 * SidebarModel.createButtonX() + SidebarModel.createButtonW();
        for (int tw : new int[] {0, 1, 40, 43, 60, 77}) {
            int textLeft = SidebarModel.createButtonTextX(tw);
            int textCentre2 = 2 * textLeft + tw; // doubled centre: no division rounding
            assertTrue(Math.abs(textCentre2 - frameCentre2) <= 1,
                "text of width " + tw + " must sit on the button frame centre (within a px)");
        }
        // A symmetric frame makes frame centring identical to full-column centring, so a
        // second, hand-copied formula (the three screens used centerX(0, WIDTH)) can never
        // drift from this one.
        assertEquals(UiLayout.centerX(0, SidebarModel.WIDTH, 40), SidebarModel.createButtonTextX(40));
        assertEquals(UiLayout.centerX(0, SidebarModel.WIDTH, 63), SidebarModel.createButtonTextX(63));
    }

    @Test void createButtonSharesTheBottomBarRow() {
        // The button is centred in a 26px bottom bar, so it sits on the same line as the
        // input row's icons. Pinned against the 2.5.0 bug where it floated a full bar's
        // height above the window bottom with a dead strip beneath it.
        int columnBottom = 320;
        int btnTop = SidebarModel.createButtonY(columnBottom);
        int listEnd = SidebarModel.listBottom(columnBottom);
        int btnBottom = btnTop + SidebarModel.CREATE_BTN_H;
        assertTrue(listEnd < btnTop, "the list must end above the button frame");
        assertEquals(SidebarModel.CREATE_LIST_GAP, btnTop - listEnd);
        assertEquals(3, SidebarModel.CREATE_BOTTOM_INSET,
            "(26 - 20) / 2 — the button's slack inside the bottom bar");
        assertEquals(SidebarModel.CREATE_BOTTOM_INSET, columnBottom - btnBottom,
            "the button is centred in the bar, not flush against the window bottom");
        // 26 is ChatLayout.BAR_H; the forge/neo wiring test binds the two symbols directly.
        assertEquals(26, SidebarModel.BOTTOM_BAR_H, "must mirror ChatLayout.BAR_H");
    }

    @Test void createButtonHitUsesTheDrawnFrame() {
        // The hit band must be exactly the frame drawn from createButtonX/createButtonW,
        // so a hard-coded draw (old `4`/`WIDTH-8`) can never drift from the hit again.
        int columnBottom = 320;
        int top = SidebarModel.createButtonY(columnBottom);
        int left = SidebarModel.createButtonX();
        int right = left + SidebarModel.createButtonW();
        assertEquals(SidebarModel.Row.CREATE, hit(top, left, 0, 0, 0, columnBottom), "top-left corner");
        assertEquals(SidebarModel.Row.CREATE, hit(top + SidebarModel.CREATE_BTN_H, right, 0, 0, 0, columnBottom),
            "bottom-right corner");
        assertEquals(SidebarModel.Row.NONE, hit(top, left - 1, 0, 0, 0, columnBottom), "one px left of the frame");
        assertEquals(SidebarModel.Row.NONE, hit(top, right + 1, 0, 0, 0, columnBottom), "one px right of the frame");
    }

    // ---- hit testing ----

    @Test void worldRowIsFullWidthAndInclusive() {
        assertEquals(SidebarModel.Row.WORLD, hit(19, 0, 0, 0, 0, 278));
        assertEquals(SidebarModel.Row.WORLD, hit(41, 89, 0, 0, 0, 278), "upper edge 19+22");
        assertEquals(SidebarModel.Row.NONE, hit(42, 40, 0, 0, 0, 278), "gap above the caption");
    }

    @Test void groupCaptionIsNotAClickTarget() {
        // caption at 45 falls in the gap between the world row and the first group row
        assertEquals(SidebarModel.Row.NONE, hit(45, 4, 3, 0, 0, 278));
    }

    @Test void groupRowsAreHitInclusive() {
        assertEquals(SidebarModel.Row.GROUP, hit(57, 40, 3, 0, 0, 278));
        assertEquals(0, hitIndex(57, 40, 3, 0, 0, 278));
        assertEquals(SidebarModel.Row.GROUP, hit(127, 40, 3, 0, 0, 278), "row2 105+22 inclusive");
        assertEquals(2, hitIndex(127, 40, 3, 0, 0, 278));
        assertEquals(SidebarModel.Row.NONE, hit(130, 40, 3, 0, 0, 278));
    }

    @Test void createButtonOnlyInItsOwnBandAndSidebarX() {
        assertEquals(SidebarModel.Row.CREATE, hit(297, 40, 0, 0, 0, 320), "top edge");
        assertEquals(SidebarModel.Row.CREATE, hit(317, 40, 0, 0, 0, 320), "bottom edge 297+20");
        assertEquals(SidebarModel.Row.NONE, hit(318, 40, 0, 0, 0, 320));
        assertEquals(SidebarModel.Row.NONE, hit(297, 2, 0, 0, 0, 320), "left of the 6px inset");
        assertEquals(SidebarModel.Row.NONE, hit(297, 88, 0, 0, 0, 320), "right of the 6px inset");
    }

    @Test void longGroupListCannotShadowTheCreateButton() {
        // 20 groups, scrolled to the tail: the visible block stays above the button, so a
        // click in the button band selects CREATE, never a group row (uireview.md §2.3).
        int scroll = SidebarModel.groupMaxScroll(20);
        assertEquals(SidebarModel.Row.CREATE, hit(303, 40, 20, 0, scroll, 320));
        assertEquals(SidebarModel.Row.GROUP, hit(153, 40, 20, 0, scroll, 320), "last visible row");
    }

    @Test void playerRowsFollowTheScrollOffset() {
        assertEquals(SidebarModel.Row.PLAYER, hit(81, 40, 0, 2, 0, 278));
        assertEquals(0, hitIndex(81, 40, 0, 2, 0, 278));
        assertEquals(SidebarModel.Row.PLAYER, hit(103, 40, 0, 2, 0, 278), "row0 81+22 inclusive");
        assertEquals(SidebarModel.Row.PLAYER, hit(105, 40, 0, 2, 0, 278), "row1 top");
        assertEquals(1, hitIndex(105, 40, 0, 2, 0, 278));
    }

    @Test void scrolledPlayerRowIsClippedAtTheListTop() {
        // offset 12 puts row0 across startY: only its [startY, scrollY+H] part is drawn,
        // so only that part must be clickable (uireview.md §2.2: the missing lower bound).
        assertEquals(SidebarModel.Row.NONE, hit(75, 40, 0, 2, 12, 278),
            "the scissored half-row above the list top must not be clickable");
        assertEquals(SidebarModel.Row.PLAYER, hit(85, 40, 0, 2, 12, 278));
        assertEquals(0, hitIndex(85, 40, 0, 2, 12, 278));
    }

    @Test void squeezedPlayerListHasNoHits() {
        // group block caps playerStartY at 201; a 220px-tall column leaves listEnd 193, so
        // no player row fits. The band just under the list is the dead gap before the
        // button (which now shares the bottom bar at the column's very foot).
        int listEnd = SidebarModel.listBottom(220);
        assertEquals(SidebarModel.Row.NONE, hit(listEnd + 1, 40, 20, 3, 0, 220));
        assertEquals(SidebarModel.Row.CREATE, hit(SidebarModel.createButtonY(220), 40, 20, 3, 0, 220),
            "the button takes the vertical space the squeezed list could not use");
    }

    // ---- helpers ----

    private static SidebarModel.Row hit(int y, int x, int groups, int players, int scroll, int columnBottom) {
        return SidebarModel.hit(y, x, groups, players, scroll, 0, columnBottom).row();
    }

    private static int hitIndex(int y, int x, int groups, int players, int scroll, int columnBottom) {
        return SidebarModel.hit(y, x, groups, players, scroll, 0, columnBottom).index();
    }
}
