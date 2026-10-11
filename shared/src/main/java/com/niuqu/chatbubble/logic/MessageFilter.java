package com.niuqu.chatbubble.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Message visibility predicate, shared by the three targets.
 *
 * <p>Pure: no Minecraft types. A row is a {@link Row} = (channel, system flag, text);
 * the {@code view} is the active tab id ({@link ChannelStateMachine#TAB_ALL} /
 * {@code #world} / {@code #system} / a group key).
 *
 * <p><b>Hard rule (master plan §Global Constraints 4):</b> a public system message
 * (join/leave/death/server prompt/denial) is visible under <em>any</em> active view.
 * It must never be swallowed by a channel filter — this is the one behaviour a screen
 * may not override.
 *
 * <p>Player chatter follows the tab: {@code #all} keeps world rows (groups are
 * quarantined in their own tab, 2.4.10 behaviour), a group/system tab keeps only its
 * own channel. A {@code null} view means "no filter" (group feature disabled).
 */
public final class MessageFilter {
    private MessageFilter() {}

    /** One feed row. {@code channel} is the world tab id or a group key. */
    public record Row(String channel, boolean system, String text) {}

    /**
     * The hard rule plus channel routing.
     *
     * @param channel the row's channel ({@code #world} or a group key)
     * @param system  the row is a public system message
     * @param view    the active tab id, or null for "no filter"
     */
    public static boolean visible(String channel, boolean system, String view) {
        if (system) return true;              // hard rule: system rows are never filtered out
        if (view == null) return true;        // filtering disabled
        if (ChannelStateMachine.TAB_ALL.equals(view)) {
            return ChannelStateMachine.TAB_WORLD.equals(channel); // 全部 = world only
        }
        return view.equals(channel);
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
