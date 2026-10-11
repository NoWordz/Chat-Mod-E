package com.niuqu.chatbubble.store;

import com.niuqu.chatbubble.chat.WhisperCandidate;
import com.niuqu.chatbubble.chat.WhisperMessageMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeenPlayerSnapshotTest {
    @Test
    void onlineAndCachedDuplicateAliasesStayAmbiguous() {
        List<ChatMessageStore.SeenPlayer> original = ChatMessageStore.seenPlayerSnapshot();
        ChatMessageStore.clearSeenPlayers();
        try {
            UUID onlineId = UUID.randomUUID();
            ChatMessageStore.rememberPlayer(UUID.randomUUID(), "CacheOne", "[VIP]Anna");
            ChatMessageStore.rememberPlayer(UUID.randomUUID(), "CacheTwo", "[VIP]Anna");

            List<ChatMessageStore.SeenPlayer> snapshot = ChatMessageStore.seenPlayerSnapshot();
            assertEquals(2, snapshot.size());
            var candidates = new ArrayList<WhisperCandidate>();
            candidates.add(new WhisperCandidate(onlineId, "[VIP]Anna", "OnlineAnna"));
            for (var seen : snapshot) {
                candidates.add(new WhisperCandidate(seen.uuid(), seen.profileName(), seen.profileName()));
                candidates.add(new WhisperCandidate(seen.uuid(), seen.displayName(), seen.profileName()));
            }

            assertNull(WhisperMessageMatcher.findIncoming(
                "[VIP]Anna悄悄地对你说：hi", candidates, "Me"));
        } finally {
            ChatMessageStore.clearSeenPlayers();
            for (var seen : original) {
                ChatMessageStore.rememberPlayer(seen.uuid(), seen.profileName(), seen.displayName());
            }
        }
    }
}
