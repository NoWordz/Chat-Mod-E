package com.niuqu.chatbubble.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Message visibility predicate, shared by the three targets.
 *
 * <p>Pure: no Minecraft types. A row is a {@link Row} = (channel, system flag, text);
 * the {@code view} is the active tab id ({@link ChannelStateMachine#TAB_ALL} /
 * {@code #world} / {@code #system} / a group key), or {@code null} for
 * "no filter" (the group feature is off).
 *
 * <p>This is the exact predicate the three screens and the sidebar previews use
 * ({@code GroupChannelState.filterMessages} / {@code matchesTab}), extracted so a
 * single implementation decides it:
 * <ul>
 *   <li>{@code null} — no filter: every row is visible;</li>
 *   <li>{@code #all} — 全部 = world + system; group rows stay quarantined in their
 *       own tab (2.4.10 behaviour). This is the default view and the one the
 *       sidebar's 「世界频道」 row selects when leaving a group, so a public
 *       system row (join/leave/death/server prompt) is visible by default and is
 *       never lost by leaving a group;</li>
 *   <li>{@code #world} / {@code #system} — the explicit player-only / system-only
 *       filters: only their own channel shows;</li>
 *   <li>a group key — only that group's rows.</li>
 * </ul>
 */
public final class MessageFilter {
    private MessageFilter() {}

    /** One feed row. {@code channel} is the world tab id or a group key. */
    public record Row(String channel, boolean system, String text) {}

    /**
     * Whether a row is visible under {@code view}.
     *
     * @param channel the row's channel ({@code #world} for player world chat,
     *                {@code #system} for a public system row, otherwise a group key)
     * @param system  the row is a public system message
     * @param view    the active tab id, or null for "no filter"
     */
    public static boolean visible(String channel, boolean system, String view) {
        if (view == null) return true;                        // filtering disabled
        if (ChannelStateMachine.TAB_ALL.equals(view)) {
            // 全部 = world + system; a group row is quarantined in its own tab.
            return !isGroupChannel(channel);
        }
        return view.equals(channel);
    }

    /** A channel that names a concrete group (not one of the pseudo tab ids). */
    private static boolean isGroupChannel(String channel) {
        return channel != null
            && !ChannelStateMachine.TAB_WORLD.equals(channel)
            && !ChannelStateMachine.TAB_SYSTEM.equals(channel)
            && !ChannelStateMachine.TAB_ALL.equals(channel);
    }

    /** Apply {@link #visible} to a whole feed, preserving order. */
    public static List<Row> filter(List<Row> feed, String view) {
        List<Row> out = new ArrayList<>();
        for (Row r : feed) {
            if (visible(r.channel(), r.system(), view)) out.add(r);
        }
        return out;
    }
}
