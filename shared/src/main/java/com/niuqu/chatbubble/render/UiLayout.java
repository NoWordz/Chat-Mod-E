package com.niuqu.chatbubble.render;

/**
 * Small layout helpers shared by the three targets.
 *
 * <p>The sidebar channel-column geometry (row rhythm, group-block window, pinned
 * create button) lives in {@code shared/logic/SidebarModel}, which is the single
 * source of truth the three screens call. This class keeps only the generic
 * centring/clamping helpers.
 */
public final class UiLayout {
    private UiLayout() {}

    public static int centerX(int containerLeft, int containerWidth, int elemWidth) {
        return containerLeft + (containerWidth - elemWidth) / 2;
    }

    public static int clampX(int x, int minX) {
        return Math.max(x, minX);
    }

    public static int clampW(int x, int w, int maxX) {
        if (x + w > maxX) return maxX - x;
        return w;
    }
}
