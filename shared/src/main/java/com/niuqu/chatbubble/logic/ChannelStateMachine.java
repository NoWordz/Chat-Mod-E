package com.niuqu.chatbubble.logic;

import java.util.Collection;

/**
 * Client-side tab / channel state machine, shared by the three targets.
 *
 * <p>Pure: no Minecraft types. It models the sidebar's channel column and the tab
 * strip: {@link #TAB_ALL} = 全部 (world + system, groups stay in their own tab),
 * {@link #TAB_WORLD} = public player messages, {@link #TAB_SYSTEM} = system
 * messages, otherwise a group key. Pseudo ids start with {@code '#'} and group keys
 * never do (server-side validation), so they cannot collide.
 *
 * <p>Two invariants this class owns (both were live bugs in {@code _e33_250/uireview.md}):
 * <ul>
 *   <li>leaving a group tab via the 「世界频道」 row selects {@code #all}, never
 *       {@code #world} (the world filter drops system rows);</li>
 *   <li>a whisper view never keeps a group tab active — the old {@code groupView()}
 *       misjudged a whisper view as a group view (§5).</li>
 * </ul>
 */
public final class ChannelStateMachine {

    public static final String TAB_ALL = "#all";
    public static final String TAB_WORLD = "#world";
    public static final String TAB_SYSTEM = "#system";

    private String active = TAB_ALL;
    private String whisperPartner;

    public String active() {
        return active;
    }

    /** The private-chat partner, or null for a non-whisper view. */
    public String whisperPartner() {
        return whisperPartner;
    }

    public boolean isWhisper() {
        return whisperPartner != null;
    }

    /** True when a real group channel (not a pseudo tab and not a whisper view) is active. */
    public boolean isGroup() {
        return !isWhisper() && !isPseudoTab(active);
    }

    public static boolean isPseudoTab(String tab) {
        return TAB_ALL.equals(tab) || TAB_WORLD.equals(tab) || TAB_SYSTEM.equals(tab);
    }

    /** Select a pseudo tab (全部/世界/系统). Returns true if anything changed. */
    public boolean selectTab(String tab) {
        return apply(tab == null ? TAB_ALL : tab, null);
    }

    /** Select a group channel key; ends any whisper view. Returns true if changed. */
    public boolean selectGroup(String groupKey) {
        return apply(groupKey, null);
    }

    /** Select a player's whisper view; drops any group tab. Returns true if changed. */
    public boolean selectWhisper(String partner) {
        return apply(TAB_ALL, partner);
    }

    /**
     * The 「世界频道」 row. It must be able to leave a group tab: it selects {@code #all}
     * (never {@code #world}, whose filter drops system rows). On a pseudo tab it leaves
     * the tab alone (2.4.17 parity) but still ends a whisper view.
     */
    public boolean selectWorldRow() {
        boolean wasWhisper = isWhisper();
        String target = isGroup() ? TAB_ALL : active;
        return apply(target, null) || wasWhisper;
    }

    /**
     * Reconcile with a fresh group directory. If the active group vanished (left,
     * deleted, disbanded) fall back to {@code #all}. Pseudo tabs are left alone.
     */
    public boolean applyDirectory(Collection<String> knownGroups) {
        if (isGroup() && (knownGroups == null || !knownGroups.contains(active))) {
            return apply(TAB_ALL, null);
        }
        return false;
    }

    /** Disconnect / world switch: forget everything. */
    public void reset() {
        active = TAB_ALL;
        whisperPartner = null;
    }

    private boolean apply(String tab, String partner) {
        if (tab.equals(active) && java.util.Objects.equals(partner, whisperPartner)) {
            return false;
        }
        active = tab;
        whisperPartner = partner;
        return true;
    }
}
