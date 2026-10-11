package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C one bounded page of authorized group history, answering a
 * {@link GroupHistoryRequestPacket}. Pages are chained by the client from the
 * member's own window floor upwards, so a live row that arrived first can never
 * leave a hole under it.
 */
public class GroupHistoryPagePacket {

    /** Server page bound (GroupHistoryPage rejects more than 100 rows). */
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_TEXT = 2048;
    private static final int MAX_NAME = 256;
    /** Hostile count from the wire: preallocate at most this many slots. */
    private static final int MAX_PREALLOC = 1024;

    private final GroupHistoryPage page;

    public GroupHistoryPagePacket(GroupHistoryPage page) {
        this.page = page;
    }

    public GroupHistoryPage page() {
        return page;
    }

    public static void encode(GroupHistoryPagePacket packet, FriendlyByteBuf buf) {
        GroupHistoryPage page = packet.page;
        buf.writeUUID(page.groupId());
        buf.writeLong(page.nextSequence());
        buf.writeBoolean(page.hasMore());
        List<GroupHistoryEntry> entries = page.entries();
        buf.writeVarInt(entries.size());
        for (GroupHistoryEntry entry : entries) {
            buf.writeLong(entry.sequence());
            buf.writeUUID(entry.senderUUID());
            buf.writeUtf(entry.senderName(), MAX_NAME);
            buf.writeUtf(entry.content(), MAX_TEXT);
            buf.writeLong(entry.time());
            buf.writeUtf(entry.replyContent() != null ? entry.replyContent() : "", MAX_TEXT);
            buf.writeUtf(entry.replySender() != null ? entry.replySender() : "", MAX_NAME);
        }
    }

    public static GroupHistoryPagePacket decode(FriendlyByteBuf buf) {
        UUID groupId = buf.readUUID();
        long nextSequence = Math.max(buf.readLong(), 0L);
        boolean hasMore = buf.readBoolean();
        int count = Math.max(buf.readVarInt(), 0);
        List<GroupHistoryEntry> entries = new ArrayList<>(Math.min(count, MAX_PREALLOC));
        long previous = -1;
        for (int i = 0; i < count; i++) {
            long sequence = buf.readLong();
            UUID senderUUID = buf.readUUID();
            String senderName = buf.readUtf(MAX_NAME);
            String content = buf.readUtf(MAX_TEXT);
            long time = buf.readLong();
            String replyContent = blankToNull(buf.readUtf(MAX_TEXT));
            String replySender = blankToNull(buf.readUtf(MAX_NAME));
            // Consume every row, keep only a well-formed bounded page: a hostile
            // row must drop itself, never throw out of the codec or break the
            // ordering the page contract requires.
            if (entries.size() >= MAX_ENTRIES) continue;
            if (sequence < 1 || time < 0 || sequence <= previous) continue;
            entries.add(new GroupHistoryEntry(groupId, sequence, senderUUID, senderName, content,
                time, replyContent, replySender));
            previous = sequence;
        }
        return new GroupHistoryPagePacket(new GroupHistoryPage(groupId, nextSequence, hasMore, entries));
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // TODO(M5): the client paging chain consumes this page (scope check against
        // the held directory, cursor advance, follow-up request, store insert); the
        // v2 client pipeline lands in M5/M6. The wire shape is frozen here.
        ctx.get().setPacketHandled(true);
    }
}
