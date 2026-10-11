package com.niuqu.chatbubble.chat.capture;

import com.niuqu.chatbubble.chat.WhisperCandidate;
import com.niuqu.chatbubble.chat.WhisperMatch;
import com.niuqu.chatbubble.chat.WhisperMessageMatcher;
import com.niuqu.chatbubble.store.ChatMessageStore;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** Resolves formatted system whispers through one shared identity matcher. */
public final class WhisperDetector {
    private WhisperDetector() {}

    public static ChatMessageStore.SenderMeta detectWhisperInSystemMessage(String text, String logTag) {
        var self = MinecraftClient.getInstance().player;
        if (self == null) return null;

        var candidates = new ArrayList<WhisperCandidate>();
        for (var info : self.networkHandler.getPlayerList()) {
            String profileName = info.getProfile().getName();
            for (String alias : ChatClassifier.nameCandidates(info)) {
                candidates.add(new WhisperCandidate(info.getProfile().getId(), alias, profileName));
            }
        }
        for (var seen : ChatMessageStore.seenPlayerSnapshot()) {
            candidates.add(new WhisperCandidate(seen.uuid(), seen.profileName(), seen.profileName()));
            if (seen.displayName() != null && !seen.displayName().isEmpty()) {
                candidates.add(new WhisperCandidate(seen.uuid(), seen.displayName(), seen.profileName()));
            }
        }

        String cleanText = text.replaceAll("§.", "");
        WhisperMatch match = WhisperMessageMatcher.findIncoming(
            cleanText, candidates, self.getName().getString());
        if (match == null) return null;

        ChatMessageStore.debugLog(() -> "[e33chat] System(" + logTag + ") | text='" + cleanText
            + "' | name=" + match.displayName() + " | content='" + match.content() + "'");
        return new ChatMessageStore.SenderMeta(
            match.senderId(), Text.literal(match.displayName()), Text.literal(match.content()),
            false, match.profileName(), true, match.profileName());
    }
}
