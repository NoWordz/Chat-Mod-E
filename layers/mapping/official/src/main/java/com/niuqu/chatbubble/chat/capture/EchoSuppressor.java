package com.niuqu.chatbubble.chat.capture;

import com.niuqu.chatbubble.chat.WhisperSignal;
import com.niuqu.chatbubble.chat.WhisperMessageMatcher;
import com.niuqu.chatbubble.store.ChatMessageStore;
import net.minecraft.client.Minecraft;

/**
 * Outgoing whisper-echo suppression decision (the text-path branch that runs
 * on the system channel when the translation key is gone — NCR-converted
 * servers). The key-path branch stays inside ChatClassifier.classifyByKey for
 * now; both share the same store state machine.
 *
 * Extracted from ChatListenerMixin during the 2.3.14 restructure. The 2.5.0
 * message slice replaced the "no other player found, so it must be us" rule
 * with WhisperMessageMatcher.isConfirmedOutgoingEcho: an echo is only
 * suppressed when the direction is explicit.
 */
public final class EchoSuppressor {
    private EchoSuppressor() {}

    /** @return true when sysText is our own outgoing whisper echo and was suppressed. */
    public static boolean trySuppressOutgoingEcho(String sysText) {
        boolean hasEchoFlag = ChatMessageStore.hasPendingWhisperEcho();
        boolean hasKw = WhisperSignal.containsZh(sysText)
            || WhisperSignal.EN.matcher(sysText.toLowerCase()).find();
        ChatMessageStore.debugLog(() -> "[e33chat] System(echo check) | text='" + sysText + "' | flag=" + hasEchoFlag + " | kw=" + hasKw);
        if (hasEchoFlag && hasKw) {
            var player = Minecraft.getInstance().player;
            String myName = player != null ? player.getName().getString() : null;
            String skipTarget = ChatMessageStore.getPendingWhisperTarget();
            if (!WhisperMessageMatcher.isConfirmedOutgoingEcho(sysText, myName, skipTarget)) return false;
            ChatMessageStore.consumeWhisperEcho();
            ChatMessageStore.markSuppressCapture();
            ChatMessageStore.debugLog(() -> "[e33chat] System(echo suppressed) | text='" + sysText + "'");
            return true;
        }
        return false;
    }
}
