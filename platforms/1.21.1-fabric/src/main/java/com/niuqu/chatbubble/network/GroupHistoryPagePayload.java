package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C bounded page of durable group history (2.5.0 v2). The page DTO is the
 * shared {@link GroupHistoryPage}, so {@code hasMore} always reflects the next
 * actually visible row instead of "latest sequence &gt; cursor".
 */
public record GroupHistoryPagePayload(GroupHistoryPage page) implements CustomPayload {

    /** Decode-side row cap: mirrors the shared page bound (100). */
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_NAME = 256;
    private static final int MAX_TEXT = 2048;

    public static final CustomPayload.Id<GroupHistoryPagePayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_history_page_v2"));

    public static final PacketCodec<PacketByteBuf, GroupHistoryPagePayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            GroupHistoryPage page = value.page();
            buf.writeUuid(page.groupId());
            buf.writeLong(page.nextSequence());
            buf.writeBoolean(page.hasMore());
            List<GroupHistoryEntry> entries = page.entries() != null ? page.entries() : List.of();
            buf.writeVarInt(entries.size());
            for (GroupHistoryEntry entry : entries) {
                buf.writeLong(entry.sequence());
                buf.writeUuid(entry.senderUUID());
                buf.writeString(entry.senderName(), MAX_NAME);
                buf.writeString(entry.content(), MAX_TEXT);
                buf.writeLong(entry.time());
                buf.writeString(entry.replyContent() != null ? entry.replyContent() : "", MAX_TEXT);
                buf.writeString(entry.replySender() != null ? entry.replySender() : "", MAX_NAME);
            }
        },
        buf -> {
            UUID groupId = buf.readUuid();
            long nextSequence = buf.readLong();
            boolean hasMore = buf.readBoolean();
            int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_ENTRIES);
            List<GroupHistoryEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long sequence = buf.readLong();
                UUID senderUUID = buf.readUuid();
                String senderName = buf.readString(MAX_NAME);
                String content = buf.readString(MAX_TEXT);
                long time = buf.readLong();
                String replyContent = blankToNull(buf.readString(MAX_TEXT));
                String replySender = blankToNull(buf.readString(MAX_NAME));
                GroupHistoryEntry entry = entry(groupId, sequence, senderUUID, senderName, content,
                    time, replyContent, replySender);
                // A malformed row is dropped instead of aborting the whole page.
                if (entry != null) entries.add(entry);
            }
            GroupHistoryPage page;
            try {
                page = new GroupHistoryPage(groupId, nextSequence, hasMore, entries);
            } catch (RuntimeException invalid) {
                page = new GroupHistoryPage(groupId, Math.max(nextSequence, 0L), false, List.of());
            }
            return new GroupHistoryPagePayload(page);
        }
    );

    private static GroupHistoryEntry entry(UUID groupId, long sequence, UUID senderUUID, String senderName,
                                           String content, long time, String replyContent, String replySender) {
        try {
            return new GroupHistoryEntry(groupId, sequence, senderUUID, senderName, content,
                time, replyContent, replySender);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * Client-side hook, invoked from ChatBubbleClientSetup's receiver.
     *
     * <p>{@code GroupChannelState} owns the paging rules: it rejects a stale or
     * out-of-window page outright, dedupes rows below the cursor, and only lets
     * the cursor move forward — live traffic never advances it, so a live row
     * arriving mid-backfill cannot skip the rows underneath it. Deduplication by
     * {@code (groupId, sequence)} happens in the store, so an overlap between a
     * live row and its paged twin still yields one bubble.
     */
    public static void handleClient(GroupHistoryPagePayload payload) {
        com.niuqu.chatbubble.chat.GroupChannelState.PageOutcome outcome =
            com.niuqu.chatbubble.chat.GroupChannelState.acceptHistoryPage(payload.page());
        if (outcome == null) return;
        com.niuqu.chatbubble.store.ChatMessageStore.addGroupHistoryEntries(outcome.accepted());
        com.niuqu.chatbubble.chat.GroupChannelState.PageRequest next = outcome.next();
        if (next != null && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                .canSend(GroupHistoryRequestPayload.ID)) {
            GroupHistoryRequestPayload.send(next.groupId(), next.after(), next.limit());
        }
    }
}
