package com.niuqu.chatbubble.logic;

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
 *  - list bottom 252 / button 256 for a 278px msg area -> _e33_250/uireview.md §3
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
        // the button is pinned to the list bottom; the capped block must stay above it
        int listEnd = SidebarModel.listBottom(278);
        assertTrue(SidebarModel.playerStartY(50) < listEnd,
            "50 groups must not push the player list into/past the button strip");
        assertEquals(252, listEnd);
        assertEquals(256, SidebarModel.createButtonY(278));
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
        assertEquals(SidebarModel.Row.CREATE, hit(256, 40, 0, 0, 0, 278), "top edge");
        assertEquals(SidebarModel.Row.CREATE, hit(276, 40, 0, 0, 0, 278), "bottom edge");
        assertEquals(SidebarModel.Row.NONE, hit(277, 40, 0, 0, 0, 278));
        assertEquals(SidebarModel.Row.NONE, hit(256, 2, 0, 0, 0, 278), "left of the 4px inset");
        assertEquals(SidebarModel.Row.NONE, hit(256, 88, 0, 0, 0, 278), "right of the 4px inset");
    }

    @Test void longGroupListCannotShadowTheCreateButton() {
        // 20 groups, scrolled to the tail: the visible block stays above the button, so a
        // click in the button band selects CREATE, never a group row (uireview.md §2.3).
        int scroll = SidebarModel.groupMaxScroll(20);
        assertEquals(SidebarModel.Row.CREATE, hit(260, 40, 20, 0, scroll, 278));
        assertEquals(SidebarModel.Row.GROUP, hit(153, 40, 20, 0, scroll, 278), "last visible row");
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
        // group block caps playerStartY at 201; a 220px panel leaves listEnd 194 -> nothing
        assertEquals(SidebarModel.Row.NONE, hit(196, 40, 20, 3, 0, 220));
    }

    // ---- helpers ----

    private static SidebarModel.Row hit(int y, int x, int groups, int players, int scroll, int panelBottom) {
        return SidebarModel.hit(y, x, groups, players, scroll, 0, panelBottom).row();
    }

    private static int hitIndex(int y, int x, int groups, int players, int scroll, int panelBottom) {
        return SidebarModel.hit(y, x, groups, players, scroll, 0, panelBottom).index();
    }
}
