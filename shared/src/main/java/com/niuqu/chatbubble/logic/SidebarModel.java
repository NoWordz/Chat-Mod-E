package com.niuqu.chatbubble.logic;

/**
 * Sidebar channel-column geometry and hit testing, shared by the three targets.
 *
 * <p>Platform-independent: this file must stay free of {@code net.minecraft} and any
 * loader API (the zero-MC guard in {@code tools/verify_targets.py} enforces it). The
 * screens translate a mouse event into {@link #hit} and route on the returned
 * {@link Row}; they own only the drawing.
 *
 * <p>Geometry facts pinned against the live sidebars at baseline {@code 26e1e92e}:
 * <ul>
 *   <li>row height 22 / step 24 — {@code ChatSidebar.java:22 ITEM_H=22}, advance {@code ITEM_H+2}</li>
 *   <li>world row top 19 — {@code 2 + SEARCH_H(14) + 3}</li>
 *   <li>group title 45, first group row 57 — {@code _e33_250/uireview.md §1}</li>
 *   <li>list bottom / create button for a 278px area: 248 / 252 — {@code uireview.md §3}</li>
 * </ul>
 */
public final class SidebarModel {
    private SidebarModel() {}

    /** Sidebar width — the coordinate space every x here lives in. */
    public static final int WIDTH = 90;
    /** Row height (the old local {@code ITEM_H}). */
    public static final int ROW_H = 22;
    /** Row advance, i.e. height + 2px gap. */
    public static final int ROW_STEP = 24;
    /** The fixed 「世界频道」 row top (2 + SEARCH_H + 3). */
    public static final int PUBLIC_Y = 19;
    /** Gap above the 「我的群」 caption. */
    public static final int GROUP_GAP = 2;
    /** Height of the 「我的群」 caption band. */
    public static final int GROUP_TITLE_H = 12;
    /** Group rows shown at once; the block scrolls beyond this. */
    public static final int GROUP_MAX_ROWS = 5;
    /** Height of the pinned 「＋ 创建群组」 button. */
    public static final int CREATE_BTN_H = 20;
    /**
     * Left/right inset of the create button inside the sidebar. Both the draw and the
     * hit test read this: the frame spans {@code CREATE_INSET .. WIDTH - CREATE_INSET}
     * (see {@link #createButtonX()} / {@link #createButtonW()}), so it is symmetrically
     * centred in the column instead of edge-flush.
     */
    public static final int CREATE_INSET = 6;
    /** Gap between the create button's lower edge and the bottom edge of the list area. */
    public static final int CREATE_BOTTOM_INSET = 6;
    /** Strip the create button keeps clear above itself, i.e. below the scrolling lists. */
    public static final int CREATE_LIST_GAP = 4;
    /**
     * Invite-picker rows shown at once. The picker scrolls (row-granular) beyond
     * this, so a long group list can never grow the popup past the panel edge.
     */
    public static final int SUBMENU_MAX_ROWS = 6;

    // ---- layout ----

    /** Caption baseline y; -1 when there are no groups. */
    public static int groupTitleY(int groupCount) {
        return groupCount <= 0 ? -1 : PUBLIC_Y + ROW_STEP + GROUP_GAP;
    }

    /** First group row's top; -1 when there are no groups. */
    public static int groupRowsTop(int groupCount) {
        int title = groupTitleY(groupCount);
        return title < 0 ? -1 : title + GROUP_TITLE_H;
    }

    /** Group row {@code index} top in the unscrolled block; -1 when there are no groups. */
    public static int groupRowY(int groupCount, int index) {
        int top = groupRowsTop(groupCount);
        return top < 0 ? -1 : top + index * ROW_STEP;
    }

    /** Group rows visible at once (the scroll window size). */
    public static int groupVisibleRows(int groupCount) {
        return Math.max(0, Math.min(groupCount, GROUP_MAX_ROWS));
    }

    /** Largest first-visible-row index (0 when every group fits). */
    public static int groupMaxFirstRow(int groupCount) {
        return Math.max(0, groupCount - GROUP_MAX_ROWS);
    }

    /** First group row visible for a pixel scroll offset, clamped to the block. */
    public static int groupFirstRow(int groupCount, int scroll) {
        return Math.min(Math.max(0, scroll / ROW_STEP), groupMaxFirstRow(groupCount));
    }

    /** Exclusive end of the visible group-row window. */
    public static int groupWindowEnd(int groupCount, int firstRow) {
        return Math.min(groupCount, Math.max(0, firstRow) + GROUP_MAX_ROWS);
    }

    /**
     * Row y of group row {@code index} inside the window starting at {@code firstRow}:
     * the offset is subtracted here, so scrolling swaps the visible rows instead of
     * sliding them down over the create button.
     */
    public static int groupWindowRowY(int groupCount, int index, int firstRow) {
        int base = groupRowY(groupCount, index);
        return base < 0 ? -1 : base - Math.max(0, firstRow) * ROW_STEP;
    }

    /** Scroll limit of the group block, in pixels (row granular). */
    public static int groupMaxScroll(int groupCount) {
        return groupMaxFirstRow(groupCount) * ROW_STEP;
    }

    /** Top of the group rows when at least one exists (57); independent of the count. */
    public static int groupBlockTop() {
        return PUBLIC_Y + ROW_STEP + GROUP_GAP + GROUP_TITLE_H;
    }

    /**
     * Lower bound of the group block: the row right after the visible group window.
     * The pinned 「＋ 创建群组」 button is bottom-aligned by {@link #createButtonY},
     * but this y is the block's own lower bound (wheel region / clipping).
     */
    public static int createRowY(int groupCount) {
        return groupBlockTop() + groupVisibleRows(groupCount) * ROW_STEP;
    }

    /** Top of the player list, after the public row and the (capped) group block. */
    public static int playerStartY(int groupCount) {
        return createRowY(groupCount) + ROW_STEP;
    }

    /** Left edge of the 「＋ 创建群组」 button frame inside the sidebar. */
    public static int createButtonX() {
        return CREATE_INSET;
    }

    /** Width of the 「＋ 创建群组」 button frame (equal insets on both sides). */
    public static int createButtonW() {
        return WIDTH - 2 * CREATE_INSET;
    }

    /** Top of the bottom-pinned 「＋ 创建群组」 button, from the list area's bottom edge. */
    public static int createButtonY(int panelBottom) {
        return panelBottom - CREATE_BTN_H - CREATE_BOTTOM_INSET;
    }

    /**
     * Text x for a label of {@code textWidth}, centred inside the button frame rather
     * than the full column. For the symmetric frame the two coincide, but deriving it
     * from the frame keeps the label honest if the insets ever diverge — and keeps the
     * three screens from growing a second, hand-copied centring formula.
     */
    public static int createButtonTextX(int textWidth) {
        return createButtonX() + (createButtonW() - textWidth) / 2;
    }

    /** Bottom of the scrolling lists: the create button keeps its own strip below it. */
    public static int listBottom(int panelBottom) {
        return createButtonY(panelBottom) - CREATE_LIST_GAP;
    }

    // ---- hit testing ----

    /** What a sidebar click landed on. */
    public enum Row {
        /** Nothing the sidebar owns (the caller may fall through). */
        NONE,
        /** The fixed 「世界频道」 row. */
        WORLD,
        /** A group row; {@link Hit#index()} is its index into the group list. */
        GROUP,
        /** A player (whisper) row; {@link Hit#index()} is its index into the player list. */
        PLAYER,
        /** The pinned 「＋ 创建群组」 button. */
        CREATE
    }

    /** A hit result: which row, and which entry within that row's list. */
    public record Hit(Row row, int index) {
        public static final Hit NONE = new Hit(Row.NONE, -1);
    }

    /**
     * Hit test in sidebar-local coordinates ({@code x} is already relative to the
     * sidebar's left edge, {@code y} is the screen mouse y).
     *
     * @param groupCount   groups in the channel column
     * @param playerCount  visible player rows (already filtered by the caller)
     * @param scrollOffset player-list pixel scroll
     * @param groupScroll  group-block pixel scroll
     * @param panelBottom  bottom edge of the list area (msgBottom, or its fallback)
     */
    public static Hit hit(int y, int x, int groupCount, int playerCount,
                          int scrollOffset, int groupScroll, int panelBottom) {
        int listEnd = listBottom(panelBottom);
        int createY = createButtonY(panelBottom);

        if (y >= PUBLIC_Y && y <= PUBLIC_Y + ROW_H) {
            return new Hit(Row.WORLD, -1);
        }

        int firstRow = groupFirstRow(groupCount, groupScroll);
        int lastRow = groupWindowEnd(groupCount, firstRow);
        for (int i = firstRow; i < lastRow; i++) {
            int gy = groupWindowRowY(groupCount, i, firstRow);
            if (gy + ROW_H > listEnd) break; // drawn clipped there too
            if (y >= gy && y <= gy + ROW_H && x >= 0) {
                return new Hit(Row.GROUP, i);
            }
        }

        if (y >= createY && y <= createY + CREATE_BTN_H
            && x >= CREATE_INSET && x <= WIDTH - CREATE_INSET) {
            return new Hit(Row.CREATE, -1);
        }

        int startY = playerStartY(groupCount);
        int scrollY = startY - scrollOffset;
        for (int k = 0; k < playerCount; k++) {
            // Clip to the visible band: the half-row above startY and the half-row past
            // the create button are drawn clipped, so only their visible part reacts.
            if (scrollY + ROW_H > startY && scrollY < listEnd
                && y >= Math.max(scrollY, startY)
                && y <= Math.min(scrollY + ROW_H, listEnd)) {
                return new Hit(Row.PLAYER, k);
            }
            scrollY += ROW_STEP;
        }
        return Hit.NONE;
    }
}
