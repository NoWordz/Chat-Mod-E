package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C one bounded page of authorized group history, answering a
 * {@link GroupHistoryRequestPayload}. Pages are chained by the client from the
 * member's own window floor upwards, so a live row that arrived first can never
 * leave a hole under it.
 */
public record GroupHistoryPagePayload(GroupHistoryPage page) implements CustomPacketPayload {

    /** Server page bound (GroupHistoryPage rejects more than 100 rows). */
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_TEXT = 2048;
    private static final int MAX_NAME = 256;
    /** Hostile count from the wire: preallocate at most this many slots. */
    private static final int MAX_PREALLOC = 1024;

    public static final Type<GroupHistoryPagePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "group_history_page_v2"));

    public static final StreamCodec<ByteBuf, GroupHistoryPagePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GroupHistoryPagePayload decode(ByteBuf buf) {
            UUID groupId = readUuid(buf);
            long nextSequence = Math.max(buf.readLong(), 0L);
            boolean hasMore = buf.readBoolean();
            int count = Math.max(readVarInt(buf), 0);
            List<GroupHistoryEntry> entries = new ArrayList<>(Math.min(count, MAX_PREALLOC));
            long previous = -1;
            for (int i = 0; i < count; i++) {
                long sequence = buf.readLong();
                UUID senderUUID = readUuid(buf);
                String senderName = readString(buf, MAX_NAME);
                String content = readString(buf, MAX_TEXT);
                long time = buf.readLong();
                String replyContent = blankToNull(readString(buf, MAX_TEXT));
                String replySender = blankToNull(readString(buf, MAX_NAME));
                // Consume every row, keep only a well-formed bounded page: a hostile
                // row must drop itself, never throw out of the codec or break the
                // ordering the page contract requires.
                if (entries.size() >= MAX_ENTRIES) continue;
                if (sequence < 1 || time < 0 || sequence <= previous) continue;
                entries.add(new GroupHistoryEntry(groupId, sequence, senderUUID, senderName, content,
                    time, replyContent, replySender));
                previous = sequence;
            }
            return new GroupHistoryPagePayload(new GroupHistoryPage(groupId, nextSequence, hasMore, entries));
        }

        @Override
        public void encode(ByteBuf buf, GroupHistoryPagePayload payload) {
            GroupHistoryPage page = payload.page();
            writeUuid(buf, page.groupId());
            buf.writeLong(page.nextSequence());
            buf.writeBoolean(page.hasMore());
            List<GroupHistoryEntry> entries = page.entries();
            writeVarInt(buf, entries.size());
            for (GroupHistoryEntry entry : entries) {
                buf.writeLong(entry.sequence());
                writeUuid(buf, entry.senderUUID());
                writeString(buf, entry.senderName(), MAX_NAME);
                writeString(buf, entry.content(), MAX_TEXT);
                buf.writeLong(entry.time());
                writeString(buf, entry.replyContent() != null ? entry.replyContent() : "", MAX_TEXT);
                writeString(buf, entry.replySender() != null ? entry.replySender() : "", MAX_NAME);
            }
        }
    };

    // Raw ByteBuf lacks the varint/utf/uuid helpers — route through FriendlyByteBuf
    private static void writeVarInt(ByteBuf buf, int value) {
        new FriendlyByteBuf(buf).writeVarInt(value);
    }

    private static int readVarInt(ByteBuf buf) {
        return new FriendlyByteBuf(buf).readVarInt();
    }

    private static void writeUuid(ByteBuf buf, UUID id) {
        new FriendlyByteBuf(buf).writeUUID(id);
    }

    private static UUID readUuid(ByteBuf buf) {
        return new FriendlyByteBuf(buf).readUUID();
    }

    private static void writeString(ByteBuf buf, String s, int cap) {
        new FriendlyByteBuf(buf).writeUtf(s, cap);
    }

    private static String readString(ByteBuf buf, int cap) {
        return new FriendlyByteBuf(buf).readUtf(cap);
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    @Override
    public Type<GroupHistoryPagePayload> type() { return TYPE; }

    public static void handleClient(GroupHistoryPagePayload payload, IPayloadContext context) {
        // TODO(M5): the client paging chain consumes this page (scope check against
        // the held directory, cursor advance, follow-up request, store insert); the
        // v2 client pipeline lands in M5/M6. The wire shape is frozen here.
    }
}
