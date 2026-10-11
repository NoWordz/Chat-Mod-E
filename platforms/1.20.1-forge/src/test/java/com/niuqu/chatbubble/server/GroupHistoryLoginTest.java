package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.group.GroupAccess;
import com.niuqu.chatbubble.packets.HistoryPacket;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupHistoryLoginTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-00000000a003");
    private static final String GUILD = "Guild-A";

    @Test
    void publicRowsAreAlwaysSentAndLegacyGroupRowsOnlyToAuthorizedReaders() {
        // Real authorization input: the migrated group's own durable access roster.
        GroupAccess migrated = new GroupAccess(OWNER, starts(OWNER, MEMBER), Set.of(OWNER, MEMBER));
        List<HistoryPacket.HistoryEntry> snapshot = List.of(
            entry(null, "public-one"),
            entry(GUILD, "legacy-owner"),
            entry(GUILD, "legacy-member"));

        List<HistoryPacket> memberSent = new ArrayList<>();
        PublicHistoryLoginSync.sendOnLogin(MEMBER, snapshot, authorization(migrated), memberSent::add);
        assertEquals(1, memberSent.size());
        assertEquals(List.of("public-one", "legacy-owner", "legacy-member"), decode(memberSent.get(0)).contents());
    }

    @Test
    void migratedMemberReceivesAuthorizedLegacyHistory() {
        GroupAccess migrated = new GroupAccess(OWNER, starts(OWNER, MEMBER), Set.of(MEMBER));
        List<HistoryPacket> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER,
            List.of(entry(null, "public-one"), entry(GUILD, "legacy-member")),
            authorization(migrated), sent::add);

        assertEquals(1, sent.size());
        assertEquals(List.of("public-one", "legacy-member"), decode(sent.get(0)).contents());
    }

    @Test
    void outsiderNeverReceivesLegacyGroupRows() {
        GroupAccess migrated = new GroupAccess(OWNER, starts(OWNER, MEMBER), Set.of(OWNER, MEMBER));
        List<HistoryPacket> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(OUTSIDER,
            List.of(entry(null, "public-one"), entry(GUILD, "legacy-member")),
            authorization(migrated), sent::add);

        assertEquals(1, sent.size());
        DecodedHistory decoded = decode(sent.get(0));
        assertEquals(List.of("public-one"), decoded.contents());
        assertEquals(List.of(), decoded.groups());
    }

    @Test
    void memberWhoJoinedAfterMigrationIsNotALegacyReader() {
        GroupAccess joinedLater = new GroupAccess(OWNER, starts(OWNER, MEMBER), Set.of(OWNER));
        List<HistoryPacket> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER,
            List.of(entry(null, "public-one"), entry(GUILD, "legacy-member")),
            authorization(joinedLater), sent::add);

        assertEquals(List.of("public-one"), decode(sent.get(0)).contents());
    }

    @Test
    void departedMemberStopsReceivingLegacyRows() {
        GroupAccess afterLeaving = new GroupAccess(OWNER, starts(OWNER), Set.of());
        List<HistoryPacket> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER, List.of(entry(GUILD, "legacy-member")),
            authorization(afterLeaving), sent::add);

        assertTrue(sent.isEmpty(), "group-only snapshot with no authorized rows must not send an empty packet");
    }

    @Test
    void groupOnlySnapshotDoesNotSendAnEmptyPublicHistoryPacket() {
        List<HistoryPacket> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER, List.of(entry(GUILD, "private")),
            (id, name) -> false, sent::add);

        assertEquals(List.of(), sent);
    }

    private static BiPredicate<UUID, String> authorization(GroupAccess access) {
        return (id, name) -> GUILD.equals(name) && access.canReadLegacy(id);
    }

    private static LinkedHashMap<UUID, Long> starts(UUID... members) {
        LinkedHashMap<UUID, Long> map = new LinkedHashMap<>();
        for (UUID member : members) map.put(member, 1L);
        return map;
    }

    private static HistoryPacket.HistoryEntry entry(String group, String content) {
        return new HistoryPacket.HistoryEntry(
            UUID.fromString("00000000-0000-0000-0000-0000000000f1"),
            "Steve", content, 1_000L, false, null, null, group);
    }

    private static DecodedHistory decode(HistoryPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            HistoryPacket.encode(packet, buf);
            int count = buf.readInt();
            List<String> contents = new ArrayList<>(count);
            List<String> groups = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                buf.readUUID();
                buf.readUtf();
                contents.add(buf.readUtf());
                buf.readLong();
                buf.readBoolean();
                buf.readUtf();
                buf.readUtf();
                String group = buf.readUtf();
                if (!group.isEmpty()) groups.add(group);
            }
            return new DecodedHistory(contents, groups);
        } finally {
            buf.release();
        }
    }

    private record DecodedHistory(List<String> contents, List<String> groups) {}
}
