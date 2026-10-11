package com.niuqu.chatbubble.group;

/**
 * Visibility contract for group lifecycle feedback (2.5.0 M4.3).
 *
 * <p>A lifecycle result — success or refusal — must reach the actor no matter which
 * client tab is open. The actor is normally sitting in the group's own tab while it
 * renames, invites or kicks, and the client tab filter
 * ({@code GroupChannelState.filterMessages}) drops every system chat row that does not
 * belong to the active tab, so a feedback row delivered as ordinary chat is invisible
 * exactly when it matters — the in-game report「改名/邀请 什么也不发生」, audit
 * GroupsServerLogic finding 2. The one channel that filter never touches is the
 * action-bar overlay, so every group lifecycle result is routed there.
 *
 * <p>Platform-free, so the lane choice and its filter-proof property are testable
 * without a client. The platform test drives the real tab filter to confirm the chat
 * lane is the swallowed one.
 */
public final class GroupFeedback {

    /** Where a feedback message is delivered. */
    public enum Lane {
        /** Action-bar overlay: drawn over any screen and never tab-filtered. */
        OVERLAY,
        /** Ordinary system chat row: subject to the active group tab's filter. */
        CHAT
    }

    private GroupFeedback() {}

    /**
     * The lane a lifecycle result must use. Group action results — the only non-null
     * keys the backend emits — always use {@link Lane#OVERLAY}; a {@code null} key is
     * background cleanup (an expiry sweep) with nothing to show the actor.
     */
    public static Lane laneFor(String messageKey) {
        return messageKey == null ? null : Lane.OVERLAY;
    }

    /**
     * Whether a row on {@code lane} survives the client's active-tab filter. The
     * overlay is never a chat row, so the filter cannot drop it; the chat lane is
     * filtered by the active tab (see the platform test).
     */
    public static boolean survivesTabFilter(Lane lane) {
        return lane == Lane.OVERLAY;
    }
}
