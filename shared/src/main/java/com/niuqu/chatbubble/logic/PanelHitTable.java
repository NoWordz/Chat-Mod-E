package com.niuqu.chatbubble.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered rectangle→action table for popups and menus, shared by the three targets.
 *
 * <p>Pure: no Minecraft types. The order is the contract — {@link #hit} returns the
 * first entry that contains the point, so a screen controls which overlapping region
 * owns a click by the order in which it {@link #add}s them (this mirrors the current
 * {@code GroupBrowserPanel}, which checks the create button and per-row leave button
 * before the row body).
 *
 * <p>One instance is meant to be rebuilt every frame/click; it is not thread-safe.
 */
public final class PanelHitTable {

    /** Returned when no entry contains the point. Distinct from any positive action code. */
    public static final int NONE = -1;

    private final List<Rect> rects = new ArrayList<>();

    /** Add a rectangle (inclusive edges) with its action code. Later adds are checked later. */
    public PanelHitTable add(int x0, int y0, int x1, int y1, int action) {
        rects.add(new Rect(Math.min(x0, x1), Math.min(y0, y1),
                           Math.max(x0, x1), Math.max(y0, y1), action));
        return this;
    }

    /** First entry containing the point, or {@link #NONE}. */
    public int hit(int x, int y) {
        for (Rect r : rects) {
            if (r.contains(x, y)) return r.action;
        }
        return NONE;
    }

    /** True when any entry contains the point. */
    public boolean contains(int x, int y) {
        return hit(x, y) != NONE;
    }

    public int size() {
        return rects.size();
    }

    public boolean isEmpty() {
        return rects.isEmpty();
    }

    public void clear() {
        rects.clear();
    }

    private record Rect(int x0, int y0, int x1, int y1, int action) {
        boolean contains(int x, int y) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1;
        }
    }
}
