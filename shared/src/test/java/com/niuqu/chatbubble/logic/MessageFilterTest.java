package com.niuqu.chatbubble.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the message-visibility predicate the three screens and the sidebar previews
 * share. The tab ids are {@code #all} / {@code #world} / {@code #system} / a group
 * key; a row is a (channel, system) pair.
 *
 * <p>The contract that matters and is easy to get wrong:
 * <ul>
 *   <li>全部 ({@code #all}) keeps a public system row (join/leave/death/server
 *       prompt) — this is the default view and the one the sidebar's 「世界频道」
 *       row selects when leaving a group, so a system row is never lost by leaving
 *       a group;</li>
 *   <li>a group tab never shows another channel's row (世界频道 row from a group
 *       view was the reported leak), including a public system row — the M4 refusal
 *       feedback therefore rides the overlay lane, not a chat row;</li>
 *   <li>{@code #world} is the explicit player-only filter, so it excludes system
 *       and group rows;</li>
 *   <li>a null view means "no filter" (the group feature is off).</li>
 * </ul>
 */
class MessageFilterTest {

    private static final String ALL = "#all";
    private static final String WORLD = "#world";
    private static final String SYSTEM = "#system";
    private static final String GROUP = "#group-1";

    // ---- pseudo tabs ----

    @Test void allKeepsWorldAndSystemButQuarantinesGroups() {
        assertTrue(MessageFilter.visible(WORLD, false, ALL));
        assertTrue(MessageFilter.visible(SYSTEM, true, ALL));
        assertFalse(MessageFilter.visible(GROUP, false, ALL),
            "全部 = world + system; group rows stay in their own tab");
    }

    @Test void worldIsThePlayerOnlyFilter() {
        assertTrue(MessageFilter.visible(WORLD, false, WORLD));
        assertFalse(MessageFilter.visible(SYSTEM, true, WORLD),
            "the 世界 tab excludes system rows (its published 2.4.x meaning)");
        assertFalse(MessageFilter.visible(GROUP, false, WORLD));
    }

    @Test void systemTabKeepsOnlySystemRows() {
        assertTrue(MessageFilter.visible(SYSTEM, true, SYSTEM));
        assertFalse(MessageFilter.visible(WORLD, false, SYSTEM));
    }

    // ---- group views ----

    @Test void groupViewShowsOnlyItsOwnChannel() {
        assertTrue(MessageFilter.visible(GROUP, false, GROUP));
        assertFalse(MessageFilter.visible("#group-2", false, GROUP),
            "another group's row must never leak into this group's tab");
        assertFalse(MessageFilter.visible(WORLD, false, GROUP),
            "a world row must not leak into a group tab");
    }

    @Test void systemRowIsFilteredOutOfAGroupTab() {
        // This is the premise of the M4 overlay lane: a chat-lane refusal would be
        // swallowed here, so group feedback is delivered by overlay instead.
        assertFalse(MessageFilter.visible(SYSTEM, true, GROUP));
    }

    @Test void leavingAGroupToAllRestoresTheSystemRow() {
        // B1 regression (user-report 2026-10-11): the sidebar's 「世界频道」 row must
        // leave a group by selecting 全部, not 世界. In 世界 the system row is hidden;
        // in 全部 it is visible. This pins the exact reason the row selects 全部.
        assertFalse(MessageFilter.visible(SYSTEM, true, WORLD),
            "selecting 世界 while leaving a group hid system messages");
        assertTrue(MessageFilter.visible(SYSTEM, true, ALL),
            "selecting 全部 keeps the system messages visible");
    }

    // ---- no filter ----

    @Test void nullViewDisablesFiltering() {
        assertTrue(MessageFilter.visible(WORLD, false, null));
        assertTrue(MessageFilter.visible(SYSTEM, true, null));
        assertTrue(MessageFilter.visible(GROUP, false, null));
    }

    // ---- list filtering: the whole predicate applied to a feed ----

    @Test void filteringAllKeepsWorldAndSystemDropsGroupChatter() {
        List<MessageFilter.Row> feed = List.of(
            new MessageFilter.Row(WORLD, false, "hello"),
            new MessageFilter.Row(SYSTEM, true, "Steve joined"),
            new MessageFilter.Row(GROUP, false, "group chatter"));
        List<MessageFilter.Row> shown = MessageFilter.filter(feed, ALL);
        assertEquals(List.of("hello", "Steve joined"),
            shown.stream().map(MessageFilter.Row::text).toList());
    }

    @Test void filteringAGroupViewKeepsOnlyThatGroup() {
        List<MessageFilter.Row> feed = List.of(
            new MessageFilter.Row(WORLD, false, "hello"),
            new MessageFilter.Row(SYSTEM, true, "Steve joined"),
            new MessageFilter.Row(GROUP, false, "group chatter"),
            new MessageFilter.Row("#group-2", false, "other"));
        List<MessageFilter.Row> shown = MessageFilter.filter(feed, GROUP);
        assertEquals(List.of("group chatter"),
            shown.stream().map(MessageFilter.Row::text).toList());
    }

    @Test void nullViewKeepsEveryRow() {
        List<MessageFilter.Row> feed = List.of(
            new MessageFilter.Row(GROUP, false, "group chatter"),
            new MessageFilter.Row(SYSTEM, true, "Steve joined"));
        assertEquals(2, MessageFilter.filter(feed, null).size());
    }
}
