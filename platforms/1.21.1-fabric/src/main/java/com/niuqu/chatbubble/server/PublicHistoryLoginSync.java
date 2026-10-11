package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.network.HistoryPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Sends the legacy public-history payload at the production login boundary.
 *
 * <p>Public rows travel to everyone; a row tagged with a group name is legacy
 * (pre-2.5) data and only reaches a recipient the group authorization still
 * covers, so a joiner can never read a group's old lines by accident.
 */
public final class PublicHistoryLoginSync {
    private PublicHistoryLoginSync() {}

    /**
     * @param recipientId         the joining player
     * @param snapshot            the server's public history buffer
     * @param legacyAuthorization production value is {@link GroupManager#canReadLegacy(UUID, String)}:
     *                            current member and still in the migrated v1 cohort
     * @param transport           sends the payload to the joiner
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
                continue;
            }
            if (legacyAuthorization != null && legacyAuthorization.test(recipientId, group)) rows.add(entry);
        }
        if (!rows.isEmpty()) transport.accept(new HistoryPayload(rows));
    }
}
