package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.group.GroupAccess;
import com.niuqu.chatbubble.network.HistoryPayload;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Login boundary: public rows reach everyone, legacy group rows only reach a
 * recipient the production authorization still covers.
 *
 * <p>The predicate under test is the production shape
 * ({@code GroupManager::canReadLegacy} = current member AND still in the migrated
 * cohort), bound here to a real {@link GroupAccess} instead of a stub, so the
 * member / newcomer / outsider / leaver cases exercise the actual rule.
 */
class GroupHistoryLoginTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID NEWCOMER = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID EX_MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final String GROUP = "Guild-A";

    /** OWNER and MEMBER were migrated; NEWCOMER joined after the migration. */
    private static GroupAccess migratedAccess() {
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>();
        starts.put(OWNER, 1L);
        starts.put(MEMBER, 1L);
        starts.put(NEWCOMER, 9L);
        return new GroupAccess(OWNER, starts, new LinkedHashSet<>(List.of(OWNER, MEMBER)));
    }

    private static BiPredicate<UUID, String> authorization(GroupAccess access) {
        return (recipientId, groupName) -> GROUP.equals(groupName) && access.canReadLegacy(recipientId);
    }

    @Test
    void migratedMemberKeepsAuthorizedLegacyRowsAlongsidePublicOnes() {
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER, snapshot(), authorization(migratedAccess()), sent::add);

        assertEquals(1, sent.size());
        assertEquals(List.of("public-one", "a-before", "a-after"), contents(decode(sent.get(0))));
    }

    @Test
    void outsiderReceivesOnlyPublicRows() {
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(OUTSIDER, snapshot(), authorization(migratedAccess()), sent::add);

        assertEquals(1, sent.size());
        assertEquals(List.of("public-one"), contents(decode(sent.get(0))));
    }

    @Test
    void newJoinerIsAMemberButNotInTheMigratedCohort() {
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(NEWCOMER, snapshot(), authorization(migratedAccess()), sent::add);

        assertEquals(1, sent.size());
        assertEquals(List.of("public-one"), contents(decode(sent.get(0))),
            "joining after the migration must not hand out the pre-migration rows");
    }

    @Test
    void leavingDropsTheLegacyRowsWithTheMembership() {
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(EX_MEMBER, snapshot(), authorization(migratedAccess()), sent::add);

        assertEquals(1, sent.size());
        assertEquals(List.of("public-one"), contents(decode(sent.get(0))));
    }

    @Test
    void unauthorizedRecipientGetsNoPacketForAGroupOnlySnapshot() {
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(OUTSIDER, List.of(entry(GROUP, "private")),
            authorization(migratedAccess()), sent::add);

        assertEquals(List.of(), sent);
    }

    @Test
    void oneGroupsGrantNeverCarriesAnotherGroupsRows() {
        List<HistoryPayload> sent = new ArrayList<>();

        PublicHistoryLoginSync.sendOnLogin(MEMBER, List.of(entry("Guild-B", "b-only")),
            authorization(migratedAccess()), sent::add);

        assertEquals(List.of(), sent, "a different group name is not covered by this grant");
    }

    private static List<HistoryPayload.HistoryEntry> snapshot() {
        return List.of(
            entry(null, "public-one"),
            entry(GROUP, "a-before"),
            entry(GROUP, "a-after"),
            entry("Guild-B", "b-only"));
    }

    private static List<String> contents(HistoryPayload payload) {
        List<String> out = new ArrayList<>();
        for (HistoryPayload.HistoryEntry row : payload.entries()) out.add(row.content());
        return out;
    }

    private static HistoryPayload.HistoryEntry entry(String group, String content) {
        return new HistoryPayload.HistoryEntry(
            UUID.fromString("00000000-0000-0000-0000-0000000000f1"),
            "Steve", content, 1_000L, false, null, null, group);
    }

    private static HistoryPayload decode(HistoryPayload payload) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            HistoryPayload.CODEC.encode(buf, payload);
            return HistoryPayload.CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
