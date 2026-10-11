package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.group.GroupAccess;
import com.niuqu.chatbubble.packets.HistoryPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
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
        List<HistoryPayload.HistoryEntry> snapshot = List.of(
            entry(null, "public-one"),
            entry(GUILD, "legacy-owner"),
            entry(GUILD, "legacy-member"));

        List<HistoryPayload> memberSent = new ArrayList<>();
        PublicHistoryLoginSync.sendOnLogin(MEMBER, snapshot, authorization(migrated), memberSent::add);
        assertEquals(1, memberSent.size());
        assertEquals(List.of("public-one", "legacy-owner", "legacy-member"), contents(memberSent.get(0)));
    }

    @Test
    void migratedMemberReceivesAuthorizedLegacyHistory() {
        GroupAccess migrated = new GroupAccess(OWNER, starts(OWNER, MEMBER), Set.of(MEMBER));
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER,
            List.of(entry(null, "public-one"), entry(GUILD, "legacy-member")),
            authorization(migrated), sent::add);

        assertEquals(1, sent.size());
        assertEquals(List.of("public-one", "legacy-member"), contents(sent.get(0)));
    }

    @Test
    void outsiderNeverReceivesLegacyGroupRows() {
        GroupAccess migrated = new GroupAccess(OWNER, starts(OWNER, MEMBER), Set.of(OWNER, MEMBER));
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(OUTSIDER,
            List.of(entry(null, "public-one"), entry(GUILD, "legacy-member")),
            authorization(migrated), sent::add);

        assertEquals(1, sent.size());
        HistoryPayload decoded = decode(sent.get(0));
        assertEquals(List.of("public-one"),
            decoded.entries().stream().map(HistoryPayload.HistoryEntry::content).toList());
        assertEquals(List.of(),
            decoded.entries().stream().map(HistoryPayload.HistoryEntry::group).filter(g -> g != null).toList());
    }

    @Test
    void memberWhoJoinedAfterMigrationIsNotALegacyReader() {
        GroupAccess joinedLater = new GroupAccess(OWNER, starts(OWNER, MEMBER), Set.of(OWNER));
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER,
            List.of(entry(null, "public-one"), entry(GUILD, "legacy-member")),
            authorization(joinedLater), sent::add);

        assertEquals(List.of("public-one"), contents(sent.get(0)));
    }

    @Test
    void departedMemberStopsReceivingLegacyRows() {
        GroupAccess afterLeaving = new GroupAccess(OWNER, starts(OWNER), Set.of());
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER, List.of(entry(GUILD, "legacy-member")),
            authorization(afterLeaving), sent::add);

        assertTrue(sent.isEmpty(), "group-only snapshot with no authorized rows must not send an empty packet");
    }

    @Test
    void groupOnlySnapshotDoesNotSendAnEmptyPublicHistoryPacket() {
        List<HistoryPayload> sent = new ArrayList<>();

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

    private static HistoryPayload.HistoryEntry entry(String group, String content) {
        return new HistoryPayload.HistoryEntry(
            UUID.fromString("00000000-0000-0000-0000-0000000000f1"),
            "Steve", content, 1_000L, false, null, null, group);
    }

    private static List<String> contents(HistoryPayload payload) {
        return decode(payload).entries().stream().map(HistoryPayload.HistoryEntry::content).toList();
    }

    private static HistoryPayload decode(HistoryPayload payload) {
        ByteBuf buf = Unpooled.buffer();
        try {
            HistoryPayload.STREAM_CODEC.encode(buf, payload);
            return HistoryPayload.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
