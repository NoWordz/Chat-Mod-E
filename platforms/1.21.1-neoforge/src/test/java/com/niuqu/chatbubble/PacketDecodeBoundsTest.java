package com.niuqu.chatbubble;

import com.niuqu.chatbubble.packets.ChatMetaPayload;
import com.niuqu.chatbubble.packets.ConfigSyncV2Payload;
import com.niuqu.chatbubble.packets.MediaUploadPayload;
import com.niuqu.chatbubble.packets.ServerConfigDto;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hostile-input contract for decoders that take a length prefix off the wire.
 * Before the caps, a 4-byte count could make the peer preallocate an
 * arbitrarily large collection (a one-packet OOM); these pin the caps and the
 * reject-instead-of-clamp rule for media strings.
 */
class PacketDecodeBoundsTest {

    private static ByteBuf buf() { return Unpooled.buffer(); }

    /** int length prefix (FriendlyByteBuf.readUtf style). */
    private static void utf(ByteBuf b, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.writeInt(bytes.length);
        b.writeBytes(bytes);
    }

    /** varint length prefix (ByteBufCodecs.STRING_UTF8 style). */
    private static void vutf(FriendlyByteBuf b, String s) {
        b.writeUtf(s);
    }

    @Test
    void chatMetaMentionCountIsCapped() {
        // ByteBufCodecs.STRING_UTF8 prefixes each string with a varint length.
        FriendlyByteBuf b = new FriendlyByteBuf(buf());
        vutf(b, new java.util.UUID(0L, 0L).toString());
        vutf(b, "Alex"); vutf(b, "h"); vutf(b, "Steve"); vutf(b, "quoted");
        b.writeVarInt(Integer.MAX_VALUE);   // hostile mention count
        for (int i = 0; i < 200; i++) vutf(b, "t" + i);
        ChatMetaPayload p = ChatMetaPayload.STREAM_CODEC.decode(b);
        assertEquals(200, p.mentionTargets().size(),
            "count must clamp to the cap instead of trusting a hostile length");
    }

    @Test
    void configSyncV2TemplateCountIsCapped() {
        ByteBuf b = buf();
        b.writeBoolean(true);
        b.writeInt(Integer.MAX_VALUE);      // hostile chat-template count
        b.writeInt(0);
        b.writeBoolean(false);
        // The cap keeps the allocation small; running dry mid-list is the
        // acceptable failure for a malformed packet.
        assertDoesNotThrow(() -> {
            try { ConfigSyncV2Payload.STREAM_CODEC.decode(b); }
            catch (RuntimeException expectedEof) { /* fine */ }
        });
    }

    @Test
    void serverConfigDtoTemplateCountIsCapped() {
        ByteBuf b = buf();
        for (int i = 0; i < 7; i++) b.writeBoolean(false);
        FriendlyByteBuf fb = new FriendlyByteBuf(b);
        fb.writeVarInt(Integer.MAX_VALUE);   // hostile chat-template count
        fb.writeVarInt(0);
        assertDoesNotThrow(() -> {
            try { ServerConfigDto.decode(new FriendlyByteBuf(b)); }
            catch (RuntimeException expectedEof) { /* fine */ }
        });
    }

    @Test
    void mediaUploadOversizedStringIsRejectedNotClamped() {
        ByteBuf b = buf();
        b.writeLong(1L); b.writeInt(0); b.writeInt(1); b.writeInt(1);
        b.writeInt(Integer.MAX_VALUE);      // hostile content-type length
        assertThrows(RuntimeException.class, () -> MediaUploadPayload.STREAM_CODEC.decode(b),
            "an out-of-range length must be rejected, not silently clamped");
    }

    @Test
    void mediaUploadOversizedChunkIsRejected() {
        ByteBuf b = buf();
        b.writeLong(1L); b.writeInt(0); b.writeInt(1); b.writeInt(1);
        utf(b, "image/png");
        b.writeInt(Integer.MAX_VALUE);      // hostile chunk length
        assertThrows(RuntimeException.class, () -> MediaUploadPayload.STREAM_CODEC.decode(b));
    }

    // ---- 2.5.0 group v2: the handshake-bound codecs take counts and lists off
    // the wire, so they need the same hostile-input contract ----

    private static void writeDirectoryEntry(FriendlyByteBuf b, int i, long visibleFrom) {
        b.writeUUID(new java.util.UUID(0, i));
        b.writeUtf("g" + i, 64);
        b.writeUUID(new java.util.UUID(0, 0));
        b.writeVarInt(1);
        b.writeUUID(new java.util.UUID(0, 0));
        b.writeLong(visibleFrom);
        b.writeBoolean(false);
        b.writeLong(1000L);
        b.writeLong(1L);
    }

    @Test
    void groupDirectoryEntryCountIsCapped() {
        FriendlyByteBuf b = new FriendlyByteBuf(buf());
        // group_max_count is configurable up to 500; a 600-row directory is read
        // in full (so the invitation list stays aligned) but only the cap is kept.
        b.writeVarInt(600);
        for (int i = 0; i < 600; i++) writeDirectoryEntry(b, i, 1L);
        b.writeVarInt(0);   // invitations
        com.niuqu.chatbubble.packets.GroupDirectoryPayload p =
            com.niuqu.chatbubble.packets.GroupDirectoryPayload.STREAM_CODEC.decode(b);
        assertEquals(500, p.entries().size(),
            "the client keeps at most the configured directory bound, not whatever the wire says");
    }

    @Test
    void groupDirectoryMalformedEntryIsSkipped() {
        FriendlyByteBuf b = new FriendlyByteBuf(buf());
        b.writeVarInt(2);
        writeDirectoryEntry(b, 0, 0L);       // visibleFrom=0 violates the contract
        writeDirectoryEntry(b, 1, 5L);
        b.writeVarInt(0);
        com.niuqu.chatbubble.packets.GroupDirectoryPayload p =
            com.niuqu.chatbubble.packets.GroupDirectoryPayload.STREAM_CODEC.decode(b);
        assertEquals(1, p.entries().size(), "a malformed row drops itself, not the directory");
        assertEquals(5L, p.entries().get(0).visibleFromSequence());
    }

    @Test
    void groupDirectoryHostileCountDiesOnEof() {
        FriendlyByteBuf b = new FriendlyByteBuf(buf());
        b.writeVarInt(Integer.MAX_VALUE);    // hostile entry count
        for (int i = 0; i < 50; i++) writeDirectoryEntry(b, i, 1L);
        // A padded count cannot be satisfied: the packet is malformed and must be
        // dropped instead of preallocating Integer.MAX_VALUE rows.
        assertThrows(RuntimeException.class,
            () -> com.niuqu.chatbubble.packets.GroupDirectoryPayload.STREAM_CODEC.decode(b));
    }

    private static void writeHistoryRow(FriendlyByteBuf b, long sequence) {
        b.writeLong(sequence);
        b.writeUUID(new java.util.UUID(0, 1));
        b.writeUtf("Steve", 256);
        b.writeUtf("line " + sequence, 2048);
        b.writeLong(1700000000000L);
        b.writeUtf("", 2048);
        b.writeUtf("", 256);
    }

    @Test
    void groupHistoryPageEntryCountIsCapped() {
        FriendlyByteBuf b = new FriendlyByteBuf(buf());
        b.writeUUID(new java.util.UUID(0, 9));
        b.writeLong(600L);
        b.writeBoolean(true);
        b.writeVarInt(600);
        for (int i = 1; i <= 600; i++) writeHistoryRow(b, i);
        com.niuqu.chatbubble.group.GroupHistoryPage page =
            com.niuqu.chatbubble.packets.GroupHistoryPagePayload.STREAM_CODEC.decode(b).page();
        assertEquals(100, page.entries().size(),
            "the page contract allows at most 100 rows per page");
    }

    @Test
    void groupHistoryPageDropsOutOfOrderRows() {
        FriendlyByteBuf b = new FriendlyByteBuf(buf());
        b.writeUUID(new java.util.UUID(0, 9));
        b.writeLong(6L);
        b.writeBoolean(false);
        b.writeVarInt(3);
        writeHistoryRow(b, 5L);
        writeHistoryRow(b, 4L);   // goes backwards
        writeHistoryRow(b, 6L);
        com.niuqu.chatbubble.group.GroupHistoryPage page =
            com.niuqu.chatbubble.packets.GroupHistoryPagePayload.STREAM_CODEC.decode(b).page();
        assertEquals(2, page.entries().size(), "a row that breaks the ordering drops itself");
        assertEquals(5L, page.entries().get(0).sequence());
        assertEquals(6L, page.entries().get(1).sequence());
    }

    @Test
    void groupHistoryRequestClampsCursorAndLimit() {
        FriendlyByteBuf b = new FriendlyByteBuf(buf());
        b.writeUUID(new java.util.UUID(0, 9));
        b.writeLong(-5L);        // negative cursor
        b.writeVarInt(9999);     // over the page bound
        com.niuqu.chatbubble.packets.GroupHistoryRequestPayload p =
            com.niuqu.chatbubble.packets.GroupHistoryRequestPayload.STREAM_CODEC.decode(b);
        assertEquals(0L, p.after(), "a negative cursor is treated as the start");
        assertEquals(100, p.limit(), "the page bound is clamped, never exceeded");
    }
}
