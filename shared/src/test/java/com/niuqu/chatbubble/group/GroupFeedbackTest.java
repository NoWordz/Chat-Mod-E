package com.niuqu.chatbubble.group;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M4.3 feedback-visibility contract: every group action result must use a lane the
 * client's active-tab filter cannot drop.
 */
class GroupFeedbackTest {

    @Test
    void everyActionResultUsesTheOverlayLane() {
        // Every non-null key the backend emits is an action result (success or
        // refusal); all of them must go where no tab filter can reach them.
        for (String key : new String[]{
                "e33chat.group.no_target", "e33chat.group.bad_name", "e33chat.group.player_unknown",
                "e33chat.group.not_owner", "e33chat.group.owner_only", "e33chat.group.save_failed",
                "e33chat.group.invite_expired", "e33chat.group.inviter_left", "e33chat.group.full",
                "e33chat.group.created", "e33chat.group.invited", "e33chat.group.renamed",
                "e33chat.group.kicked", "e33chat.group.transferred", "e33chat.group.deleted",
                "e33chat.group.left"}) {
            assertEquals(GroupFeedback.Lane.OVERLAY, GroupFeedback.laneFor(key), key);
        }
    }

    @Test
    void backgroundCleanupHasNoLane() {
        assertNull(GroupFeedback.laneFor(null), "an expiry sweep has nothing to show the actor");
    }

    @Test
    void onlyTheOverlayLaneSurvivesTheTabFilter() {
        assertTrue(GroupFeedback.survivesTabFilter(GroupFeedback.Lane.OVERLAY),
            "the overlay is not a chat row, so no tab filter can drop it");
        assertFalse(GroupFeedback.survivesTabFilter(GroupFeedback.Lane.CHAT),
            "a chat row is subject to the active tab");
        assertFalse(GroupFeedback.survivesTabFilter(null));
    }
}
