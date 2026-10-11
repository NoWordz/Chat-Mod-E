package com.niuqu.chatbubble.chat.capture;

import com.niuqu.chatbubble.chat.WhisperCandidate;
import com.niuqu.chatbubble.chat.WhisperMatch;
import com.niuqu.chatbubble.chat.WhisperMessageMatcher;
import com.niuqu.chatbubble.store.ChatMessageStore;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Whisper detection on the text layer (NCR-converted servers where the chat
 * type is stripped): online-player scan first, seen-cache fallback for
 * offline senders. Keyword gate via MessagePresentation.
 *
 * Extracted from ChatListenerMixin during the 2.3.14 restructure. The 2.5.0
 * message slice moved the decision into WhisperMessageMatcher: a sender needs
 * whole-name boundaries plus an explicit recipient, and an ambiguous match is
 * reported as no match instead of the first plausible candidate.
 */
public final class WhisperDetector {
    private WhisperDetector() {}

    public static ChatMessageStore.SenderMeta detectWhisperInSystemMessage(String text, String logTag) {
        // Join-window guard: the caller can run before the local player exists.
        var self = Minecraft.getInstance().player;
        if (self == null) return null;
        var connection = self.connection;
        String ownName = self.getName().getString();
        // G3: 消息嵌 legacy 色码（S§6t§beve）时整条剥 § 再做名字锚点匹配
        String clean = text.replaceAll("§.", "");
        var candidates = new ArrayList<WhisperCandidate>();
        for (var info : connection.getOnlinePlayers()) {
            String profile = info.getProfile().getName();
            for (String cand : ChatClassifier.nameCandidates(info)) {
                candidates.add(new WhisperCandidate(info.getProfile().getId(), cand, profile));
            }
        }
        // cache fallback: try seen (offline) players
        for (var seen : ChatMessageStore.seenPlayerSnapshot()) {
            candidates.add(new WhisperCandidate(seen.uuid(), seen.profileName(), seen.profileName()));
            if (seen.displayName() != null && !seen.displayName().isEmpty()) {
                candidates.add(new WhisperCandidate(seen.uuid(), seen.displayName(), seen.profileName()));
            }
        }
        WhisperMatch match = WhisperMessageMatcher.findIncoming(clean, candidates, ownName);
        if (match == null) return null;
        ChatMessageStore.debugLog(() -> "[e33chat] System(" + logTag + ") | text='" + clean
            + "' | name=" + match.displayName() + " | content='" + match.content() + "'");
        return new ChatMessageStore.SenderMeta(
            match.senderId(),
            Component.literal(match.displayName()),
            Component.literal(match.content()),
            false,
            match.profileName(),
            true, match.profileName()
        );
    }
}
