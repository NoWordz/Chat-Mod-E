package com.niuqu.chatbubble.chat;

import com.niuqu.chatbubble.group.GroupFeedback;
import com.niuqu.chatbubble.store.ChatMessageStore.ChatMessage;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4.3 failure test: a group-action refusal must stay visible while a group tab is
 * active. It drives the real client tab filter ({@link GroupChannelState#filterMessages})
 * with a real system {@link ChatMessage} and shows that the chat lane the old
 * {@code sendSystemMessage} feedback used is dropped — the exact "改名/邀请 什么也不发生"
 * symptom (audit GroupsServerLogic finding 2). The overlay lane the backend now uses is
 * not a chat row, so the filter never sees it.
 */
class GroupFeedbackVisibilityTest {

    private static final String GROUP_TAB = "Guild";

    @AfterEach
    void resetState() {
        GroupChannelState.reset();
    }

    private static ChatMessage systemRow() {
        return new ChatMessage(new UUID(0, 0), Component.literal("Server"),
            Component.translatable("e33chat.group.owner_only"), 0L, false, true,
            null, null, "owner_only", 1, null, false, null, null);
    }

    @Test
    void groupRefusalIsInvisibleOnTheChatLaneWhileAGroupTabIsActive() {
        // The old feedback path: an ordinary system chat row. This is what the filter
        // does to it while the actor is sitting in its group's tab.
        List<ChatMessage> afterFilter = GroupChannelState.filterMessages(List.of(systemRow()), GROUP_TAB);
        assertTrue(afterFilter.isEmpty(),
            "a system chat row is filtered out of a group tab — the swallowed channel");

        // And that row is exactly what GroupFeedback refuses to use for a refusal.
        assertEquals(GroupFeedback.Lane.OVERLAY, GroupFeedback.laneFor("e33chat.group.owner_only"));
        assertFalse(GroupFeedback.survivesTabFilter(GroupFeedback.Lane.CHAT));
    }

    @Test
    void theSelectedLaneIsNeverFiltered() {
        // The lane the backend actually uses for the same refusal survives because the
        // overlay is drawn outside the message list the filter operates on.
        assertTrue(GroupFeedback.survivesTabFilter(GroupFeedback.laneFor("e33chat.group.owner_only")));

        // Control: with no active tab the filter is a pass-through, so the test patch
        // above is really exercising the group-tab branch, not a dead filter.
        assertFalse(GroupChannelState.filterMessages(List.of(systemRow()), null).isEmpty());
    }
}
