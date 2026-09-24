package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.store.ChatMessageStore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record HistoryPayload(List<HistoryEntry> entries) implements CustomPacketPayload {

    /** Cracked/offline senders already arrive as UUID(0,0); reuse it for a missing one. */
    private static final UUID NULL_UUID = new UUID(0, 0);

    public static final Type<HistoryPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "chat_history"));

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

    public static final StreamCodec<ByteBuf, HistoryPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public HistoryPayload decode(ByteBuf buf) {
            int count = Math.min(Math.max(buf.readInt(), 0), 200);
            List<HistoryEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new HistoryEntry(
                    UUID.fromString(ByteBufCodecs.STRING_UTF8.decode(buf)),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readLong(),
                    buf.readBoolean(),
                    blankToNull(ByteBufCodecs.STRING_UTF8.decode(buf)),
                    blankToNull(ByteBufCodecs.STRING_UTF8.decode(buf)),
                    blankToNull(ByteBufCodecs.STRING_UTF8.decode(buf))
                ));
            }
            return new HistoryPayload(entries);
        }

        @Override
        public void encode(ByteBuf buf, HistoryPayload payload) {
            List<HistoryEntry> entries = payload.entries();
            // Robustness, learned from a field incident: this packet is built from a
            // snapshot of the server's history buffer and encoded while the login
            // event chain is still running, so one null row used to throw an NPE that
            // cost the joining player their login ("Invalid player data"). Skip rows
            // that cannot be encoded; the count must match what is written, so
            // filter before writing it.
            int count = 0;
            if (entries != null) {
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i) != null) count++;
                }
            }
            if (entries != null && count < entries.size()) {
                // One line of evidence for "the history arrived short": dropping a row
                // is deliberate, but it must not be invisible.
                com.mojang.logging.LogUtils.getLogger().warn("[e33chat] History packet: dropped "
                    + (entries.size() - count) + " null row(s) of " + entries.size());
            }
            buf.writeInt(count);
            if (entries == null) return;
            for (int i = 0; i < entries.size(); i++) {
                HistoryEntry e = entries.get(i);
                if (e == null) continue;
                ByteBufCodecs.STRING_UTF8.encode(buf,
                    (e.senderUUID() != null ? e.senderUUID() : NULL_UUID).toString());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.senderName() != null ? e.senderName() : "");
                ByteBufCodecs.STRING_UTF8.encode(buf, e.content() != null ? e.content() : "");
                buf.writeLong(e.time());
                buf.writeBoolean(e.isSystem());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.replyContent() != null ? e.replyContent() : "");
                ByteBufCodecs.STRING_UTF8.encode(buf, e.replySender() != null ? e.replySender() : "");
                ByteBufCodecs.STRING_UTF8.encode(buf, e.group() != null ? e.group() : "");
            }
        }
    };

    @Override
    public Type<HistoryPayload> type() { return TYPE; }

    public static void handleClient(HistoryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ChatMessageStore.addHistoryMessages(payload.entries()));
    }

    private static String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
