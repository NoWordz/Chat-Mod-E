package com.niuqu.chatbubble;

import com.niuqu.chatbubble.packets.ChatMetaPacket;
import com.niuqu.chatbubble.packets.ConfigSyncPacket;
import com.niuqu.chatbubble.packets.ConfigSyncV2Packet;
import com.niuqu.chatbubble.packets.EasyBotConfigPacket;
import com.niuqu.chatbubble.packets.HistoryPacket;
import com.niuqu.chatbubble.packets.MediaCapPacket;
import com.niuqu.chatbubble.packets.MediaRequestPacket;
import com.niuqu.chatbubble.packets.MediaResponsePacket;
import com.niuqu.chatbubble.packets.MediaUploadAckPacket;
import com.niuqu.chatbubble.packets.MediaUploadPacket;
import com.niuqu.chatbubble.packets.QuoteSyncPacket;
import com.niuqu.chatbubble.packets.ServerConfigSavePacket;
import com.niuqu.chatbubble.packets.ServerConfigScreenPacket;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for every network packet's encode/decode - the wire format
 * is a hard contract, so the codec must stay byte-stable.
 * Assertion style: encode -> decode -> encode again and compare bytes, so the
 * tests do not depend on getters that some packets lack.
 */
class PacketCodecTest {

    private interface Encoder {
        void encode(Object packet, FriendlyByteBuf buf);
    }

    private interface Decoder {
        Object decode(FriendlyByteBuf buf);
    }

    private static void assertStable(Object packet, Encoder enc, Decoder dec) {
        FriendlyByteBuf b1 = new FriendlyByteBuf(Unpooled.buffer());
        enc.encode(packet, b1);
        byte[] original = Arrays.copyOf(b1.array(), b1.writerIndex());
        Object decoded = dec.decode(b1);
        FriendlyByteBuf b2 = new FriendlyByteBuf(Unpooled.buffer());
        enc.encode(decoded, b2);
        byte[] after = Arrays.copyOf(b2.array(), b2.writerIndex());
        assertArrayEquals(original, after, "encode(decode(encode(p))) must equal encode(p)");
    }

    /** Encode into a throwaway buffer and hand back the bytes for comparison. */
    private static byte[] bytes(java.util.function.Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writer.accept(buf);
        return Arrays.copyOf(buf.array(), buf.writerIndex());
    }

    @Test void quoteSyncStable() {
        assertStable(new QuoteSyncPacket("Steve", "hello [quote]", "hash123"),
            (p, buf) -> ((QuoteSyncPacket) p).encode(buf), b -> QuoteSyncPacket.decode(b));
    }

    @Test void chatMetaStable() {
        assertStable(new ChatMetaPacket(
                UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                "Alex", "mh", "Steve", "quoted content", List.of("target1", "target2")),
            (p, buf) -> ((ChatMetaPacket) p).encode(buf), b -> ChatMetaPacket.decode(b));
    }

    @Test void chatMetaEmptyFieldsStable() {
        assertStable(new ChatMetaPacket(new UUID(0, 0), "X", "h", "", "", List.of()),
            (p, buf) -> ((ChatMetaPacket) p).encode(buf), b -> ChatMetaPacket.decode(b));
    }

    @Test void historyStable() {
        assertStable(new HistoryPacket(List.of(
                new HistoryPacket.HistoryEntry(new UUID(1, 1), "Steve", "hi there", 1700000000000L, false, "reply", "sender", null),
                new HistoryPacket.HistoryEntry(new UUID(2, 2), "Alex", "system msg", 1700000001000L, true, "", "", "公会"))),
            (p, buf) -> HistoryPacket.encode((HistoryPacket) p, buf), b -> HistoryPacket.decode(b));
    }

    @Test void historyEmptyStable() {
        assertStable(new HistoryPacket(List.of()),
            (p, buf) -> HistoryPacket.encode((HistoryPacket) p, buf), b -> HistoryPacket.decode(b));
    }

    // The 2.4.0 incident: a null row in the login snapshot made encode throw an
    // NPE inside PlayerLoggedInEvent, and vanilla turned that into
    // "Couldn't place player in world" + a kick ("Invalid player data").
    // Encode must degrade to "skip that row", never to "break the login".
    // Byte-level assertions (no getters on this packet, per the style above).
    @Test void historySkipsNullEntry() {
        HistoryPacket.HistoryEntry good = new HistoryPacket.HistoryEntry(
            new UUID(3, 3), "Steve", "kept", 1700000002000L, false, null, null, "公会");
        assertArrayEquals(
            bytes(p -> HistoryPacket.encode(new HistoryPacket(List.of(good)), p)),
            bytes(p -> HistoryPacket.encode(new HistoryPacket(Arrays.asList(null, good, null)), p)),
            "null rows are dropped and the survivor keeps its bytes");
        assertArrayEquals(
            bytes(p -> HistoryPacket.encode(new HistoryPacket(List.of()), p)),
            bytes(p -> HistoryPacket.encode(new HistoryPacket(Arrays.asList(null, null)), p)),
            "an all-null packet is a legal empty list, not a half-written one");
    }

    @Test void historyNullFieldsBecomeEmpty() {
        HistoryPacket.HistoryEntry bare = new HistoryPacket.HistoryEntry(
            null, null, null, 1700000003000L, false, null, null, null);
        HistoryPacket.HistoryEntry emptyish = new HistoryPacket.HistoryEntry(
            new UUID(0, 0), "", "", 1700000003000L, false, null, null, null);
        assertDoesNotThrow(() ->
            HistoryPacket.encode(new HistoryPacket(List.of(bare)), new FriendlyByteBuf(Unpooled.buffer())));
        assertArrayEquals(
            bytes(p -> HistoryPacket.encode(new HistoryPacket(List.of(emptyish)), p)),
            bytes(p -> HistoryPacket.encode(new HistoryPacket(List.of(bare)), p)),
            "missing sender/content fall back to UUID(0,0) and \"\" (the offline-player shape)");
    }

    @Test void configSyncStable() {
        assertStable(new ConfigSyncPacket(true),
            (p, buf) -> ConfigSyncPacket.encode((ConfigSyncPacket) p, buf), b -> ConfigSyncPacket.decode(b));
    }

    @Test void configSyncV2Stable() {
        assertStable(new ConfigSyncV2Packet(true,
                List.of("{name}: {content}", "{prefix}{name} >> {content}"), List.of("{sender} -> {content}"), false),
            (p, buf) -> ConfigSyncV2Packet.encode((ConfigSyncV2Packet) p, buf), b -> ConfigSyncV2Packet.decode(b));
    }

    @Test void mediaCapStable() {
        assertStable(new MediaCapPacket(true),
            (p, buf) -> ((MediaCapPacket) p).encode(buf), b -> MediaCapPacket.decode(b));
    }

    @Test void easyBotConfigStable() {
        assertStable(new EasyBotConfigPacket(true),
            (p, buf) -> EasyBotConfigPacket.encode((EasyBotConfigPacket) p, buf), b -> EasyBotConfigPacket.decode(b));
    }

    @Test void mediaRequestStable() {
        assertStable(new MediaRequestPacket("0123456789abcdef0123456789abcdef"),
            (p, buf) -> ((MediaRequestPacket) p).encode(buf), b -> MediaRequestPacket.decode(b));
    }

    @Test void mediaResponseStable() {
        assertStable(new MediaResponsePacket("abc", 2, 5, new byte[]{1, 2, 3}),
            (p, buf) -> ((MediaResponsePacket) p).encode(buf), b -> MediaResponsePacket.decode(b));
    }

    @Test void mediaUploadAckOkStable() {
        assertStable(new MediaUploadAckPacket(42L, "mediaid", null),
            (p, buf) -> ((MediaUploadAckPacket) p).encode(buf), b -> MediaUploadAckPacket.decode(b));
    }

    @Test void mediaUploadAckErrorStable() {
        assertStable(new MediaUploadAckPacket(7L, null, "too large"),
            (p, buf) -> ((MediaUploadAckPacket) p).encode(buf), b -> MediaUploadAckPacket.decode(b));
    }

    @Test void mediaUploadStable() {
        assertStable(new MediaUploadPacket(99L, 0, 3, 3000, "image/png", new byte[]{9, 8, 7}),
            (p, buf) -> ((MediaUploadPacket) p).encode(buf), b -> MediaUploadPacket.decode(b));
    }

    @Test void serverConfigScreenStable() {
        assertStable(new ServerConfigScreenPacket(true, false, true, true, true, true, true,
                List.of("chat tpl"), List.of("whisper tpl")),
            (p, buf) -> ServerConfigScreenPacket.encode((ServerConfigScreenPacket) p, buf), b -> ServerConfigScreenPacket.decode(b));
    }

    @Test void serverConfigSaveStable() {
        assertStable(new ServerConfigSavePacket(false, true, false, false, true, true, false,
                List.of(), List.of("w")),
            (p, buf) -> ServerConfigSavePacket.encode((ServerConfigSavePacket) p, buf), b -> ServerConfigSavePacket.decode(b));
    }

    // ---- 2.5.0 group protocol v2 (independent channel, ids 0-5) ----

    private static com.niuqu.chatbubble.group.GroupDirectoryEntry directoryEntry(UUID groupId, String name) {
        return new com.niuqu.chatbubble.group.GroupDirectoryEntry(
            new com.niuqu.chatbubble.group.GroupIdentity(groupId, name),
            new UUID(7, 7), List.of(new UUID(7, 7), new UUID(8, 8)),
            12L, true, 900L, 5L);
    }

    @Test void groupProtocolHelloStable() {
        assertStable(new com.niuqu.chatbubble.packets.GroupProtocolHelloPacket(1),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupProtocolHelloPacket.encode(
                (com.niuqu.chatbubble.packets.GroupProtocolHelloPacket) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupProtocolHelloPacket.decode(b));
    }

    @Test void groupProtocolAckStable() {
        assertStable(new com.niuqu.chatbubble.packets.GroupProtocolAckPacket(1, true),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupProtocolAckPacket.encode(
                (com.niuqu.chatbubble.packets.GroupProtocolAckPacket) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupProtocolAckPacket.decode(b));
    }

    @Test void groupDirectoryStable() {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        com.niuqu.chatbubble.packets.GroupDirectoryPacket packet =
            new com.niuqu.chatbubble.packets.GroupDirectoryPacket(
                List.of(directoryEntry(groupId, "公會")),
                List.of(new com.niuqu.chatbubble.group.GroupInvitationSummary(
                    new UUID(9, 9), groupId, "公會", new UUID(7, 7), 1700000000000L, 1700000600000L)));
        assertStable(packet,
            (p, buf) -> com.niuqu.chatbubble.packets.GroupDirectoryPacket.encode(
                (com.niuqu.chatbubble.packets.GroupDirectoryPacket) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupDirectoryPacket.decode(b));
    }

    @Test void groupDirectoryEmptyStable() {
        assertStable(new com.niuqu.chatbubble.packets.GroupDirectoryPacket(List.of(), List.of()),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupDirectoryPacket.encode(
                (com.niuqu.chatbubble.packets.GroupDirectoryPacket) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupDirectoryPacket.decode(b));
    }

    @Test void groupChatV2Stable() {
        // Frozen 7-field tuple: groupId, sequence, senderUUID, senderName,
        // content, quoteSender, quoteContent (the display name comes from the
        // directory, never from this payload).
        assertStable(new com.niuqu.chatbubble.packets.GroupChatV2Packet(
                UUID.fromString("00000000-0000-0000-0000-00000000000a"), 42L,
                new UUID(3, 3), "Steve", "hello group", "Alex", "quoted text"),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupChatV2Packet.encode(
                (com.niuqu.chatbubble.packets.GroupChatV2Packet) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupChatV2Packet.decode(b));
    }

    @Test void groupChatV2BlankQuoteStable() {
        assertStable(new com.niuqu.chatbubble.packets.GroupChatV2Packet(
                UUID.fromString("00000000-0000-0000-0000-00000000000a"), 1L,
                new UUID(0, 0), "Steve", "hi", "", ""),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupChatV2Packet.encode(
                (com.niuqu.chatbubble.packets.GroupChatV2Packet) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupChatV2Packet.decode(b));
    }

    @Test void groupHistoryRequestStable() {
        assertStable(new com.niuqu.chatbubble.packets.GroupHistoryRequestPacket(
                UUID.fromString("00000000-0000-0000-0000-00000000000a"), 39L, 100),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupHistoryRequestPacket.encode(
                (com.niuqu.chatbubble.packets.GroupHistoryRequestPacket) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupHistoryRequestPacket.decode(b));
    }

    @Test void groupHistoryPageStable() {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        com.niuqu.chatbubble.group.GroupHistoryPage page = new com.niuqu.chatbubble.group.GroupHistoryPage(
            groupId, 42L, true, List.of(
                new com.niuqu.chatbubble.group.GroupHistoryEntry(groupId, 40L, new UUID(4, 4), "Steve",
                    "line 40", 1700000000040L, "quoted", "Alex"),
                new com.niuqu.chatbubble.group.GroupHistoryEntry(groupId, 41L, new UUID(4, 4), "Steve",
                    "line 41", 1700000000041L, null, null)));
        assertStable(new com.niuqu.chatbubble.packets.GroupHistoryPagePacket(page),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupHistoryPagePacket.encode(
                (com.niuqu.chatbubble.packets.GroupHistoryPagePacket) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupHistoryPagePacket.decode(b));
    }

    @Test void groupHistoryPageEmptyStable() {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        assertStable(new com.niuqu.chatbubble.packets.GroupHistoryPagePacket(
                new com.niuqu.chatbubble.group.GroupHistoryPage(groupId, 39L, false, List.of())),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupHistoryPagePacket.encode(
                (com.niuqu.chatbubble.packets.GroupHistoryPagePacket) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupHistoryPagePacket.decode(b));
    }

    // ---- 2.5.0 group v2 lifecycle and chunked directory (GROUPS_CHANNEL 6 and 9) ----

    @Test void groupActionV2StableForEveryActionCode() {
        // Byte stability also proves the action survived: a re-encoded packet with a
        // different code would not reproduce the original bytes.
        for (int action = com.niuqu.chatbubble.packets.GroupActionV2Packet.CREATE;
             action <= com.niuqu.chatbubble.packets.GroupActionV2Packet.DISBAND; action++) {
            assertStable(new com.niuqu.chatbubble.packets.GroupActionV2Packet(action,
                    com.niuqu.chatbubble.packets.GroupActionV2Packet.NONE,
                    com.niuqu.chatbubble.packets.GroupActionV2Packet.NONE,
                    com.niuqu.chatbubble.packets.GroupActionV2Packet.NONE,
                    com.niuqu.chatbubble.packets.GroupActionV2Packet.NO_VALUE),
                (p, buf) -> com.niuqu.chatbubble.packets.GroupActionV2Packet.encode(
                    (com.niuqu.chatbubble.packets.GroupActionV2Packet) p, buf),
                b -> com.niuqu.chatbubble.packets.GroupActionV2Packet.decode(b));
        }
    }

    @Test void groupActionV2CarriesEveryWireField() {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID subjectId = new UUID(1, 2);
        UUID invitationId = new UUID(3, 4);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        com.niuqu.chatbubble.packets.GroupActionV2Packet.encode(
            new com.niuqu.chatbubble.packets.GroupActionV2Packet(
                com.niuqu.chatbubble.packets.GroupActionV2Packet.INVITE,
                groupId, subjectId, invitationId, "Guild"),
            buf);
        com.niuqu.chatbubble.packets.GroupActionV2Packet decoded =
            com.niuqu.chatbubble.packets.GroupActionV2Packet.decode(buf);
        assertEquals(com.niuqu.chatbubble.packets.GroupActionV2Packet.INVITE, decoded.action());
        assertEquals(groupId, decoded.groupId());
        assertEquals(subjectId, decoded.subjectId());
        assertEquals(invitationId, decoded.invitationId());
        assertEquals("Guild", decoded.value());
    }

    @Test void groupDirectoryChunkStable() {
        assertStable(new com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket(
                UUID.fromString("00000000-0000-0000-0000-00000000000b"), 1, 3, new byte[]{1, 2, 3, 4}),
            (p, buf) -> com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket.encode(
                (com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket) p, buf),
            b -> com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket.decode(b));
    }

    @Test void groupDirectoryChunkOversizedSliceIsRejected() {
        UUID transferId = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        int max = com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket.MAX_CHUNK_BYTES;
        assertThrows(IllegalArgumentException.class,
            () -> new com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket(
                transferId, 0, 1, new byte[max + 1]),
            "a slice over the chunk budget must never be constructed");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(transferId);
        buf.writeVarInt(0);
        buf.writeVarInt(1);
        buf.writeByteArray(new byte[max + 1]);   // hostile slice length on the wire
        assertThrows(RuntimeException.class,
            () -> com.niuqu.chatbubble.packets.GroupDirectoryChunkPacket.decode(buf),
            "the wire cap rejects an oversized slice instead of allocating it");
    }
}