package com.niuqu.chatbubble.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the message visibility predicate. The hard rule (master plan constraint 4):
 * a public system message (join/leave/death/server prompt/denial) is visible under
 * <em>any</em> active view — it must never be dropped by a channel filter.
 *
 * A row is a (channel, system) pair; the view is the active tab id
 * ({@code #all} / {@code #world} / {@code #system} / a group key).
 */
class MessageFilterTest {

    private static final String ALL = "#all";
    private static final String WORLD = "#world";
    private static final String SYSTEM = "#system";
    private static final String GROUP = "#group-1";

    // ---- the hard rule ----

    @Test void systemRowIsAlwaysVisibleInEveryView() {
        for (String view : List.of(ALL, WORLD, SYSTEM, GROUP)) {
            assertTrue(MessageFilter.visible(WORLD, true, view),
                "system row must survive the view " + view);
        }
    }

    @Test void systemRowSurvivesAMismatchedChannelFilter() {
        assertTrue(MessageFilter.visible(WORLD, true, GROUP),
            "a public system row routed to the world feed must show in the group view");
    }

    @Test void playerChatterIsHiddenByAForeignChannelFilter() {
        assertFalse(MessageFilter.visible(WORLD, false, GROUP),
            "a world player message is not visible in the group view");
        assertFalse(MessageFilter.visible("#group-2", false, GROUP));
    }

    @Test void playerChatterStaysVisibleInItsOwnView() {
        assertTrue(MessageFilter.visible(WORLD, false, WORLD));
        assertTrue(MessageFilter.visible(GROUP, false, GROUP));
        assertTrue(MessageFilter.visible(WORLD, false, ALL));
    }

    @Test void allViewQuarantinesGroupChatterButKeepsWorld() {
        assertTrue(MessageFilter.visible(WORLD, false, ALL));
        assertFalse(MessageFilter.visible(GROUP, false, ALL),
            "全部 = world + system; group rows stay in their own tab");
    }

    @Test void systemRowSurvivesEvenWhenTheActiveViewIsNull() {
        // enabled == false -> screens pass a null active tab, meaning "no filter"
        assertTrue(MessageFilter.visible(WORLD, true, null));
    }

    @Test void nullViewDisablesFilteringForPlayerChatterToo() {
        assertTrue(MessageFilter.visible(GROUP, false, null));
    }

    // ---- list filtering: the whole predicate applied to a feed ----

    @Test void filteringAGroupViewKeepsEverySystemRow() {
        List<MessageFilter.Row> feed = List.of(
            new MessageFilter.Row(WORLD, false, "hello"),
            new MessageFilter.Row(WORLD, true, "Steve joined"),
            new MessageFilter.Row(GROUP, false, "group chatter"),
            new MessageFilter.Row(WORLD, true, "server restarting"));
        List<MessageFilter.Row> shown = MessageFilter.filter(feed, GROUP);
        assertEquals(List.of("Steve joined", "group chatter", "server restarting"),
            shown.stream().map(MessageFilter.Row::text).toList());
    }

    @Test void filteringAllKeepsSystemRowsButDropsForeignGroupChatter() {
        List<MessageFilter.Row> feed = List.of(
            new MessageFilter.Row(WORLD, false, "hello"),
            new MessageFilter.Row(WORLD, true, "Steve joined"),
            new MessageFilter.Row(GROUP, false, "group chatter"));
        List<MessageFilter.Row> shown = MessageFilter.filter(feed, ALL);
        assertEquals(List.of("hello", "Steve joined"),
            shown.stream().map(MessageFilter.Row::text).toList());
    }

    @Test void nullViewKeepsEveryRow() {
        List<MessageFilter.Row> feed = List.of(
            new MessageFilter.Row(GROUP, false, "group chatter"),
            new MessageFilter.Row(WORLD, true, "Steve joined"));
        assertEquals(2, MessageFilter.filter(feed, null).size());
    }
}
