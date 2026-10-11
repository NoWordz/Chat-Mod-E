package com.niuqu.chatbubble.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4.3 production-path test: the group adapter must deliver every lifecycle result on
 * the overlay lane (the one the client tab filter cannot drop). {@code overlayLane} is
 * the exact decision {@code feedback}/{@code fail} feed to
 * {@code sendMessage(..., overlay)} — so flipping a refusal back to the chat lane turns
 * this red. The companion {@code GroupFeedbackVisibilityTest} proves the chat lane
 * really is the swallowed one under an active group tab.
 */
class GroupManagerFeedbackLaneTest {

    @Test
    void everyLifecycleResultUsesTheOverlayLane() {
        // Refusals the audit named, plus the success keys, all route to the overlay.
        for (String key : new String[]{
                "e33chat.group.no_target", "e33chat.group.bad_name", "e33chat.group.player_unknown",
                "e33chat.group.not_owner", "e33chat.group.owner_only", "e33chat.group.save_failed",
                "e33chat.group.invite_expired", "e33chat.group.inviter_left",
                "e33chat.group.created", "e33chat.group.renamed", "e33chat.group.invited"}) {
            assertTrue(GroupManager.overlayLane(key), key + " must be visible over any tab");
        }
    }

    @Test
    void backgroundCleanupIsNotAnOverlay() {
        // A null key is an expiry sweep with nothing to show the actor.
        assertFalse(GroupManager.overlayLane(null));
    }
}
