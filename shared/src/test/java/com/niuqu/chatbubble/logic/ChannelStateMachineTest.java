package com.niuqu.chatbubble.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sidebar/tab channel transitions the three screens duplicate:
 * A → group → 「世界频道」 row → back to A, plus the leave-group fallback and the
 * invariant that a whisper view never keeps a group tab active (the groupView()
 * misjudge in _e33_250/uireview.md §5).
 */
class ChannelStateMachineTest {

    @Test void startsOnTheAllTab() {
        ChannelStateMachine m = new ChannelStateMachine();
        assertEquals(ChannelStateMachine.TAB_ALL, m.active());
        assertFalse(m.isGroup());
        assertFalse(m.isWhisper());
        assertNull(m.whisperPartner());
    }

    @Test void worldRowFromAGroupReturnsToAllAndNeverWorld() {
        ChannelStateMachine m = new ChannelStateMachine();
        m.selectGroup("#group-1");
        assertTrue(m.isGroup());
        assertTrue(m.selectWorldRow(), "the world row must be able to leave a group tab");
        assertEquals(ChannelStateMachine.TAB_ALL, m.active(),
            "must select 全部, not 世界频道 (that filter drops system rows)");
        assertFalse(m.isWhisper());
    }

    @Test void worldRowLeavesAPseudoTabAlone() {
        // parity with 2.4.17: on 全部/世界/系统 the row does nothing (it only leaves a group)
        ChannelStateMachine m = new ChannelStateMachine();
        m.selectTab(ChannelStateMachine.TAB_SYSTEM);
        assertFalse(m.selectWorldRow(), "no group tab -> no change");
        assertEquals(ChannelStateMachine.TAB_SYSTEM, m.active());
    }

    @Test void selectingAGroupClearsTheWhisperView() {
        ChannelStateMachine m = new ChannelStateMachine();
        m.selectWhisper("alice");
        assertTrue(m.isWhisper());
        m.selectGroup("#g");
        assertTrue(m.isGroup());
        assertFalse(m.isWhisper(), "group and whisper views are mutually exclusive");
        assertNull(m.whisperPartner());
    }

    @Test void whisperViewDropsTheGroupTab() {
        // clicking a player row must not leave a group tab active behind the whisper view
        ChannelStateMachine m = new ChannelStateMachine();
        m.selectGroup("#g");
        m.selectWhisper("alice");
        assertTrue(m.isWhisper());
        assertFalse(m.isGroup(), "whisper view is not a group view");
        assertEquals(ChannelStateMachine.TAB_ALL, m.active());
    }

    @Test void pseudoTabsAreAllWorldSystem() {
        assertTrue(ChannelStateMachine.isPseudoTab(ChannelStateMachine.TAB_ALL));
        assertTrue(ChannelStateMachine.isPseudoTab(ChannelStateMachine.TAB_WORLD));
        assertTrue(ChannelStateMachine.isPseudoTab(ChannelStateMachine.TAB_SYSTEM));
        assertFalse(ChannelStateMachine.isPseudoTab("#group-1"));
    }

    @Test void directoryUpdateFallsBackWhenTheActiveGroupDisappears() {
        ChannelStateMachine m = new ChannelStateMachine();
        m.selectGroup("#g");
        assertTrue(m.applyDirectory(List.of("#other")), "left/deleted group -> fall back");
        assertEquals(ChannelStateMachine.TAB_ALL, m.active());
    }

    @Test void directoryUpdateKeepsAnActiveGroupThatStillExists() {
        ChannelStateMachine m = new ChannelStateMachine();
        m.selectGroup("#g");
        assertFalse(m.applyDirectory(List.of("#g", "#other")));
        assertEquals("#g", m.active());
    }

    @Test void directoryUpdateLeavesPseudoTabsAlone() {
        ChannelStateMachine m = new ChannelStateMachine();
        m.selectTab(ChannelStateMachine.TAB_WORLD);
        assertFalse(m.applyDirectory(List.of()));
        assertEquals(ChannelStateMachine.TAB_WORLD, m.active());
    }

    @Test void resetGoesBackToAll() {
        ChannelStateMachine m = new ChannelStateMachine();
        m.selectWhisper("alice");
        m.reset();
        assertEquals(ChannelStateMachine.TAB_ALL, m.active());
        assertFalse(m.isWhisper());
    }
}
