package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.packets.HistoryPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Sends the legacy public-history payload at the production login boundary. */
public final class PublicHistoryLoginSync {
    private PublicHistoryLoginSync() {}

    /**
     * Public rows ({@code group == null}) always go out. Legacy group rows go only to
     * recipients the durable group roster still authorizes: current members whose
     * migrated legacy cohort contains them ({@code GroupManager::canReadLegacy} in
     * production). Everything else is dropped rather than leaked.
     */
    public static void sendOnLogin(UUID recipientId, List<HistoryPayload.HistoryEntry> snapshot,
                                   BiPredicate<UUID, String> legacyAuthorization,
                                   Consumer<HistoryPayload> transport) {
        if (snapshot == null || snapshot.isEmpty()) return;
        List<HistoryPayload.HistoryEntry> rows = new ArrayList<>(snapshot.size());
        for (HistoryPayload.HistoryEntry entry : snapshot) {
            if (entry == null) continue;
            String group = entry.group();
            if (group == null) {
                rows.add(entry);
            } else if (recipientId != null && legacyAuthorization.test(recipientId, group)) {
                rows.add(entry);
            }
        }
        if (!rows.isEmpty()) transport.accept(new HistoryPayload(rows));
    }
}
