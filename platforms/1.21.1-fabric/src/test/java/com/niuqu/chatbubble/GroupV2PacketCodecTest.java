package com.niuqu.chatbubble;

import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import com.niuqu.chatbubble.group.GroupIdentity;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import com.niuqu.chatbubble.network.GroupActionV2Payload;
import com.niuqu.chatbubble.network.GroupChatV2Payload;
import com.niuqu.chatbubble.network.GroupDirectoryChunkPayload;
import com.niuqu.chatbubble.network.GroupDirectoryPayload;
import com.niuqu.chatbubble.network.GroupHistoryPagePayload;
import com.niuqu.chatbubble.network.GroupHistoryRequestPayload;
import com.niuqu.chatbubble.network.GroupProtocolAckPayload;
import com.niuqu.chatbubble.network.GroupProtocolHelloPayload;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire contract for the 2.5.0 v2 group payloads: encode -&gt; decode -&gt;
 * encode must be byte-stable, and every length prefix coming off the wire must be
 * bounded without rejecting what a valid server may legitimately send.
 */
class GroupV2PacketCodecTest {

    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-0000000000c4");

    private static <T> void assertStable(T payload, PacketCodec<PacketByteBuf, T> codec) {
        PacketByteBuf first = new PacketByteBuf(Unpooled.buffer());
        codec.encode(first, payload);
        byte[] original = Arrays.copyOf(first.array(), first.writerIndex());
        T decoded = codec.decode(first);
        PacketByteBuf second = new PacketByteBuf(Unpooled.buffer());
        codec.encode(second, decoded);
        byte[] after = Arrays.copyOf(second.array(), second.writerIndex());
        assertArrayEquals(original, after, "encode(decode(encode(p))) must equal encode(p)");
    }

    @Test
    void helloStable() {
        assertStable(new GroupProtocolHelloPayload(GroupProtocolHelloPayload.PROTOCOL_VERSION),
            GroupProtocolHelloPayload.CODEC);
    }

    @Test
    void ackStable() {
        assertStable(new GroupProtocolAckPayload(1, true), GroupProtocolAckPayload.CODEC);
        assertStable(new GroupProtocolAckPayload(1, false), GroupProtocolAckPayload.CODEC);
        assertStable(new GroupProtocolAckPayload(2, false), GroupProtocolAckPayload.CODEC);
    }

    /**
     * Every lifecycle action travels on one record: the action code picks which UUID
     * and value slots are real, and the unused slots carry the documented placeholders
     * (the all-zero UUID and the empty string) that the server reads back.
     */
    @Test
    void actionV2RoundTripsEveryActionCodeAndItsPlaceholders() {
        UUID invitation = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
        UUID none = GroupActionV2Payload.NONE;
        String noValue = GroupActionV2Payload.NO_VALUE;
        GroupActionV2Payload[] requests = {
            new GroupActionV2Payload(GroupActionV2Payload.CREATE, none, none, none, "Guild"),
            new GroupActionV2Payload(GroupActionV2Payload.INVITE, GROUP, MEMBER, none, noValue),
            new GroupActionV2Payload(GroupActionV2Payload.ACCEPT, none, none, invitation, noValue),
            new GroupActionV2Payload(GroupActionV2Payload.REJECT, none, none, invitation, noValue),
            new GroupActionV2Payload(GroupActionV2Payload.REVOKE, none, none, invitation, noValue),
            new GroupActionV2Payload(GroupActionV2Payload.LEAVE, GROUP, none, none, noValue),
            new GroupActionV2Payload(GroupActionV2Payload.RENAME, GROUP, none, none, "Renamed"),
            new GroupActionV2Payload(GroupActionV2Payload.KICK, GROUP, MEMBER, none, noValue),
            new GroupActionV2Payload(GroupActionV2Payload.TRANSFER_OWNER, GROUP, MEMBER, none, noValue),
            new GroupActionV2Payload(GroupActionV2Payload.DISBAND, GROUP, none, none, noValue)
        };

        assertEquals(10, requests.length, "one request shape per action code");
        for (int action = 0; action < requests.length; action++) {
            assertEquals(action, requests[action].action());
            assertStable(requests[action], GroupActionV2Payload.CODEC);

            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            try {
                GroupActionV2Payload.CODEC.encode(buf, requests[action]);
                GroupActionV2Payload decoded = GroupActionV2Payload.CODEC.decode(buf);
                assertEquals(requests[action], decoded, "every wire slot must survive the round trip");
                assertEquals(0, buf.readableBytes(), "the decode must consume exactly the action frame");
            } finally {
                buf.release();
            }
        }
    }

    @Test
    void directoryRoundTripKeepsEveryField() {
        List<GroupDirectoryEntry> groups = List.of(
            new GroupDirectoryEntry(new GroupIdentity(GROUP, "公会"), OWNER,
                List.of(OWNER, MEMBER), 7L, true, 12L, 3L),
            new GroupDirectoryEntry(new GroupIdentity(UUID.randomUUID(), "Guild-B"), SENDER,
                List.of(SENDER), 1L, false, 0L, 1L));
        List<GroupInvitationSummary> invitations = List.of(
            new GroupInvitationSummary(UUID.randomUUID(), GROUP, "公会", OWNER, 10L, 20L));
        GroupDirectoryPayload payload = new GroupDirectoryPayload(groups, invitations);

        assertStable(payload, GroupDirectoryPayload.CODEC);

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        GroupDirectoryPayload decoded;
        try {
            GroupDirectoryPayload.CODEC.encode(buf, payload);
            decoded = GroupDirectoryPayload.CODEC.decode(buf);
        } finally {
            buf.release();
        }
        GroupDirectoryEntry first = decoded.groups().get(0);
        assertEquals("公会", first.identity().displayName());
        assertEquals(List.of(OWNER, MEMBER), first.memberIds());
        assertEquals(7L, first.visibleFromSequence());
        assertTrue(first.legacyHistoryAllowed());
        assertEquals(12L, first.latestSequence());
        assertEquals(3L, first.retainedFromSequence());
        assertFalse(decoded.groups().get(1).legacyHistoryAllowed());
        assertEquals(1, decoded.invitations().size());
        assertEquals(20L, decoded.invitations().get(0).expiresAtMs());
    }

    @Test
    void directoryCarriesTheLargestConfiguredServer() {
        List<GroupDirectoryEntry> groups = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            groups.add(new GroupDirectoryEntry(new GroupIdentity(UUID.randomUUID(), "g" + i), OWNER,
                List.of(OWNER), 1L, false, 0L, 1L));
        }
        assertStable(new GroupDirectoryPayload(groups, List.of()), GroupDirectoryPayload.CODEC);
    }

    @Test
    void directoryCarriesTheLargestConfiguredGroup() {
        List<UUID> members = new ArrayList<>();
        members.add(OWNER);
        for (int i = 1; i < 1000; i++) members.add(UUID.randomUUID());
        GroupDirectoryEntry entry = new GroupDirectoryEntry(
            new GroupIdentity(GROUP, "big"), OWNER, members, 1L, false, 5L, 1L);

        assertStable(new GroupDirectoryPayload(List.of(entry), List.of()), GroupDirectoryPayload.CODEC);
    }

    @Test
    void directoryHostileCountDoesNotPreallocateTheHeader() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(Integer.MAX_VALUE);
            assertDoesNotThrow(() -> {
                try {
                    GroupDirectoryPayload.CODEC.decode(buf);
                } catch (RuntimeException expectedEof) {
                    // bounded decode ran out of bytes instead of allocating the claim
                }
            });
        } finally {
            buf.release();
        }
    }

    @Test
    void directoryDropsAMalformedEntryInsteadOfTheWholePayload() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(2);
            // Entry 1: valid.
            buf.writeUuid(GROUP);
            buf.writeString("ok", 64);
            buf.writeUuid(OWNER);
            buf.writeVarInt(1);
            buf.writeUuid(OWNER);
            buf.writeLong(1L);
            buf.writeBoolean(false);
            buf.writeLong(1L);
            buf.writeLong(1L);
            // Entry 2: the owner is not among the members, which the shared DTO rejects.
            buf.writeUuid(UUID.randomUUID());
            buf.writeString("bad", 64);
            buf.writeUuid(OWNER);
            buf.writeVarInt(1);
            buf.writeUuid(MEMBER);
            buf.writeLong(1L);
            buf.writeBoolean(false);
            buf.writeLong(0L);
            buf.writeLong(1L);
            buf.writeVarInt(0);

            GroupDirectoryPayload decoded = GroupDirectoryPayload.CODEC.decode(buf);
            assertEquals(1, decoded.groups().size(), "only the valid entry survives");
            assertEquals("ok", decoded.groups().get(0).identity().displayName());
        } finally {
            buf.release();
        }
    }

    /** A trailing field written after the payload: reading it back after the decode
     *  proves the decoder consumed exactly the declared rows/members/invitations. */
    private static final int TRAILING_SENTINEL = 0x5EED_5EED;

    private static void writeDirectoryRow(PacketByteBuf buf, String name, int memberCount) {
        buf.writeUuid(GROUP);
        buf.writeString(name, 64);
        buf.writeUuid(OWNER);
        buf.writeVarInt(memberCount);
        buf.writeUuid(OWNER);                                    // the owner must be a member
        for (int m = 1; m < memberCount; m++) buf.writeUuid(UUID.randomUUID());
        buf.writeLong(1L);
        buf.writeBoolean(false);
        buf.writeLong(1L);
        buf.writeLong(1L);
    }

    private static void writeInvitation(PacketByteBuf buf) {
        buf.writeUuid(UUID.randomUUID());
        buf.writeUuid(GROUP);
        buf.writeString("inv", 64);
        buf.writeUuid(OWNER);
        buf.writeLong(1L);
        buf.writeLong(2L);
    }

    @Test
    void directoryConsumesOversizedGroupRowsWithoutSplicingTheInvitationList() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(503);                       // three rows past the 500 cap
            for (int i = 0; i < 503; i++) writeDirectoryRow(buf, "g" + i, 1);
            buf.writeVarInt(1);
            writeInvitation(buf);
            buf.writeInt(TRAILING_SENTINEL);

            GroupDirectoryPayload decoded = GroupDirectoryPayload.CODEC.decode(buf);

            assertEquals(500, decoded.groups().size(), "only the configured cap is kept");
            assertEquals(1, decoded.invitations().size(),
                "the invitation list must be read at its real offset, not out of a dropped row");
            assertEquals(2L, decoded.invitations().get(0).expiresAtMs());
            assertEquals(TRAILING_SENTINEL, buf.readInt(),
                "every declared row must be consumed, leaving the buffer on the next field");
        } finally {
            buf.release();
        }
    }

    @Test
    void directoryConsumesOversizedMemberRowsWithoutLosingAlignment() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(1);
            writeDirectoryRow(buf, "big", 1003);        // three members past the 1000 cap
            buf.writeVarInt(1);
            writeInvitation(buf);
            buf.writeInt(TRAILING_SENTINEL);

            GroupDirectoryPayload decoded = GroupDirectoryPayload.CODEC.decode(buf);

            assertEquals(1, decoded.groups().size());
            assertEquals(1000, decoded.groups().get(0).memberIds().size(),
                "surplus members are consumed but not kept");
            assertEquals(1, decoded.invitations().size());
            assertEquals(TRAILING_SENTINEL, buf.readInt());
        } finally {
            buf.release();
        }
    }

    @Test
    void directoryConsumesOversizedInvitationsWithoutLosingAlignment() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(1);
            writeDirectoryRow(buf, "g", 1);
            buf.writeVarInt(503);                       // three past the 500 cap
            for (int i = 0; i < 503; i++) writeInvitation(buf);
            buf.writeInt(TRAILING_SENTINEL);

            GroupDirectoryPayload decoded = GroupDirectoryPayload.CODEC.decode(buf);

            assertEquals(1, decoded.groups().size());
            assertEquals(500, decoded.invitations().size(), "only the configured cap is kept");
            assertEquals(TRAILING_SENTINEL, buf.readInt());
        } finally {
            buf.release();
        }
    }

    /**
     * One directory chunk is self-describing: {@code (transferId, index, count, bytes)}.
     * The client reassembles the slices and decodes the single complete directory, so the
     * codec must preserve the byte slice exactly and refuse a slice past its size budget.
     */
    @Test
    void chunkRoundTripsOneSliceAndRejectsAnOversizedSlice() {
        UUID transfer = UUID.randomUUID();
        byte[] slice = new byte[4096];
        for (int i = 0; i < slice.length; i++) slice[i] = (byte) i;

        GroupDirectoryChunkPayload chunk = new GroupDirectoryChunkPayload(transfer, 2, 5, slice);
        assertStable(chunk, GroupDirectoryChunkPayload.CODEC);

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            GroupDirectoryChunkPayload.CODEC.encode(buf, chunk);
            GroupDirectoryChunkPayload decoded = GroupDirectoryChunkPayload.CODEC.decode(buf);
            assertEquals(transfer, decoded.transferId());
            assertEquals(2, decoded.index());
            assertEquals(5, decoded.count());
            assertArrayEquals(slice, decoded.data());
            assertEquals(0, buf.readableBytes(), "the decode must consume exactly the chunk frame");
        } finally {
            buf.release();
        }

        // The declared length is what bounds the read: a frame claiming more than
        // MAX_CHUNK_BYTES must be refused instead of allocated.
        PacketByteBuf oversized = new PacketByteBuf(Unpooled.buffer());
        try {
            oversized.writeUuid(transfer);
            oversized.writeVarInt(0);
            oversized.writeVarInt(1);
            oversized.writeByteArray(new byte[GroupDirectoryChunkPayload.MAX_CHUNK_BYTES + 1]);
            assertThrows(RuntimeException.class, () -> GroupDirectoryChunkPayload.CODEC.decode(oversized));
        } finally {
            oversized.release();
        }

        // The record itself refuses to hold an over-budget slice in the first place.
        assertThrows(IllegalArgumentException.class, () -> new GroupDirectoryChunkPayload(
            transfer, 0, 1, new byte[GroupDirectoryChunkPayload.MAX_CHUNK_BYTES + 1]));
    }

    @Test
    void liveStable() {
        assertStable(new GroupChatV2Payload(GROUP, 42L, SENDER, "Steve", "hello there",
            "quoted sender", "quoted content"), GroupChatV2Payload.CODEC);
    }

    @Test
    void liveWithoutQuoteStable() {
        assertStable(new GroupChatV2Payload(GROUP, Long.MAX_VALUE, SENDER, "Steve", "hi", null, null),
            GroupChatV2Payload.CODEC);
    }

    @Test
    void historyRequestStable() {
        assertStable(new GroupHistoryRequestPayload(GROUP, 0L, 0), GroupHistoryRequestPayload.CODEC);
        assertStable(new GroupHistoryRequestPayload(UUID.randomUUID(), 99L,
            GroupHistoryRequestPayload.MAX_PAGE_LIMIT), GroupHistoryRequestPayload.CODEC);
    }

    @Test
    void historyPageStable() {
        GroupHistoryPage page = new GroupHistoryPage(GROUP, 12L, true, List.of(
            new GroupHistoryEntry(GROUP, 11L, SENDER, "Steve", "first", 1_700_000_000_000L, null, null),
            new GroupHistoryEntry(GROUP, 12L, SENDER, "Steve", "second", 1_700_000_000_001L, "quoted", "Alex")));

        assertStable(new GroupHistoryPagePayload(page), GroupHistoryPagePayload.CODEC);

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        GroupHistoryPage decoded;
        try {
            GroupHistoryPagePayload.CODEC.encode(buf, new GroupHistoryPagePayload(page));
            decoded = GroupHistoryPagePayload.CODEC.decode(buf).page();
        } finally {
            buf.release();
        }
        assertEquals(12L, decoded.nextSequence());
        assertTrue(decoded.hasMore());
        assertEquals(2, decoded.entries().size());
        assertEquals("quoted", decoded.entries().get(1).replyContent());
        assertEquals("Alex", decoded.entries().get(1).replySender());
        assertStable(new GroupHistoryPagePayload(new GroupHistoryPage(
            GROUP, Long.MAX_VALUE, false, List.of())), GroupHistoryPagePayload.CODEC);
    }

    @Test
    void historyPageAcceptsTheFullWirePage() {
        List<GroupHistoryEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            entries.add(new GroupHistoryEntry(GROUP, i, SENDER, "Steve", "m" + i, 1_000L + i, null, null));
        }
        assertStable(new GroupHistoryPagePayload(
            new GroupHistoryPage(GROUP, 100L, true, entries)), GroupHistoryPagePayload.CODEC);
    }

    @Test
    void historyPageHostileCountDoesNotPreallocateTheHeader() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeUuid(GROUP);
            buf.writeLong(0L);
            buf.writeBoolean(false);
            buf.writeVarInt(Integer.MAX_VALUE);
            assertDoesNotThrow(() -> {
                try {
                    GroupHistoryPagePayload.CODEC.decode(buf);
                } catch (RuntimeException expectedEof) {
                    // bounded decode ran out of bytes instead of allocating the claim
                }
            });
        } finally {
            buf.release();
        }
    }
}
