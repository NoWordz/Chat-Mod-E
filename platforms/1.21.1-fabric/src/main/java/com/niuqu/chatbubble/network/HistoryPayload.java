package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record HistoryPayload(List<HistoryPayload.HistoryEntry> entries)
        implements CustomPayload {

    /** Cracked/offline senders already arrive as UUID(0,0); reuse it for a missing one. */
    private static final UUID NULL_UUID = new UUID(0, 0);

    public static final CustomPayload.Id<HistoryPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "chat_history"));

    public record HistoryEntry(
        UUID senderUUID,
        String senderName,
        String content,
        long time,
        boolean isSystem,
        String replyContent,
        String replySender,
        String group
    ) {}

    public static final PacketCodec<PacketByteBuf, HistoryPayload> CODEC = PacketCodec.of(
        // Robustness, learned from a field incident: this packet is built from a
        // snapshot of the server's history buffer and encoded while the join-event
        // chain is still running, so one null row used to throw an NPE that cost the
        // joining player their login ("Invalid player data"). Rows that cannot be
        // encoded are skipped; writeCollection's count is the filtered list's size,
        // so the header can never over-count what follows.
        (value, buf) -> {
            List<HistoryEntry> rows = new ArrayList<>();
            if (value.entries != null) {
                for (int i = 0; i < value.entries.size(); i++) {
                    HistoryEntry e = value.entries.get(i);
                    if (e != null) rows.add(e);
                }
            }
            if (value.entries != null && rows.size() < value.entries.size()) {
                // One line of evidence for "the history arrived short": dropping a row
                // is deliberate, but it must not be invisible.
                com.mojang.logging.LogUtils.getLogger().warn("[e33chat] History packet: dropped "
                    + (value.entries.size() - rows.size()) + " null row(s) of "
                    + value.entries.size());
            }
            buf.writeCollection(rows, HistoryPayload::writeEntry);
        },
        buf -> {
            // Entry bound aligned with Forge/Neo (200): the count comes off the
            // wire, and even 1.21.1's readList still allows a 65536-entry
            // allocation per packet. Entries is the payload's only field, so
            // leftover bytes from an oversized count are harmless.
            int count = Math.min(Math.max(buf.readVarInt(), 0), 200);
            List<HistoryEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) entries.add(new HistoryEntry(
                UUID.fromString(buf.readString()),
                buf.readString(),
                buf.readString(),
                buf.readLong(),
                buf.readBoolean(),
                nullOrEmpty(buf.readString()),
                nullOrEmpty(buf.readString()),
                nullOrEmpty(buf.readString())
            ));
            return new HistoryPayload(entries);
        }
    );

    private static void writeEntry(PacketByteBuf buf, HistoryEntry e) {
        buf.writeString((e.senderUUID() != null ? e.senderUUID() : NULL_UUID).toString());
        buf.writeString(e.senderName() != null ? e.senderName() : "");
        buf.writeString(e.content() != null ? e.content() : "");
        buf.writeLong(e.time());
        buf.writeBoolean(e.isSystem());
        buf.writeString(e.replyContent() != null ? e.replyContent() : "");
        buf.writeString(e.replySender() != null ? e.replySender() : "");
        buf.writeString(e.group() != null ? e.group() : "");
    }

    private static String nullOrEmpty(String s) { return s == null || s.isEmpty() ? null : s; }

    @Override
    public Id<HistoryPayload> getId() { return ID; }
}
