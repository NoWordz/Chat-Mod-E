package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.store.ChatMessageStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class HistoryPacket {
    /** Cracked/offline senders already arrive as UUID(0,0); reuse it for a missing one. */
    private static final UUID NULL_UUID = new UUID(0, 0);

    private final List<HistoryEntry> entries;

    public HistoryPacket(List<HistoryEntry> entries) {
        this.entries = entries;
    }

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

    public static void encode(HistoryPacket packet, FriendlyByteBuf buf) {
        List<HistoryEntry> entries = packet.entries;
        if (entries == null || entries.isEmpty()) {
            buf.writeInt(0);
            return;
        }
        // Robustness, learned from a field incident: this packet is built from a
        // snapshot of the server's history buffer and encoded while the login event
        // chain is still running. A single null entry (or null field) threw an NPE
        // inside PlayerLoggedInEvent, which made vanilla kick the joining player
        // with "Invalid player data" - so one bad row used to take the whole packet
        // and the whole login down. Skip what cannot be encoded instead; the count
        // must match what is actually written, so filter before writing it.
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) != null) count++;
        }
        if (count < entries.size()) {

            // One line of evidence for "the history arrived short": dropping a row is

            // deliberate, but it must not be invisible.

            com.mojang.logging.LogUtils.getLogger().warn("[e33chat] History packet: dropped "

                + (entries.size() - count) + " null row(s) of " + entries.size());

        }

        buf.writeInt(count);

        for (int i = 0; i < entries.size(); i++) {
            HistoryEntry e = entries.get(i);
            if (e == null) continue;
            buf.writeUUID(e.senderUUID() != null ? e.senderUUID() : NULL_UUID);
            buf.writeUtf(e.senderName() != null ? e.senderName() : "");
            buf.writeUtf(e.content() != null ? e.content() : "");
            buf.writeLong(e.time());
            buf.writeBoolean(e.isSystem());
            buf.writeUtf(e.replyContent() != null ? e.replyContent() : "");
            buf.writeUtf(e.replySender() != null ? e.replySender() : "");
            buf.writeUtf(e.group() != null ? e.group() : "");
        }
    }

    public static HistoryPacket decode(FriendlyByteBuf buf) {
        // Guard against a hostile/broken packet: the server caps history at
        // HISTORY_MAX=50, so a huge count would OOM on preallocation
        int count = Math.min(Math.max(buf.readInt(), 0), 200);
        List<HistoryEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new HistoryEntry(
                buf.readUUID(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readLong(),
                buf.readBoolean(),
                blankToNull(buf.readUtf()),
                blankToNull(buf.readUtf()),
                blankToNull(buf.readUtf())
            ));
        }
        return new HistoryPacket(entries);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                ChatMessageStore.addHistoryMessages(entries)
            )
        );
        ctx.get().setPacketHandled(true);
    }

    private static String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
