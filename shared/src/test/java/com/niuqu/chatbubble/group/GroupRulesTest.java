package com.niuqu.chatbubble.group;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The platform-free group lifecycle rules (2.5.0 phase 5).
 *
 * <p>Every case builds a real {@link GroupFileStore.Snapshot} fixture and drives the
 * rules with real {@link GroupRules.SequenceSource}/{@link GroupRules.NameLookup}
 * lambdas (no mocks, no Minecraft, explicit timestamps), then asserts the durable
 * snapshot and outcome — never source text.
 */
class GroupRulesTest {
    private static final UUID OWNER = uuid(1);
    private static final UUID MEMBER = uuid(2);
    private static final UUID INVITEE = uuid(3);
    private static final UUID STRANGER = uuid(4);
    private static final UUID OPERATOR = uuid(5);

    private static final UUID GROUP = uuid(10);
    private static final UUID OTHER_GROUP = uuid(11);

    private static final UUID INVITE = uuid(20);
    private static final UUID INVITE_EXPIRED = uuid(21);
    private static final UUID INVITE_LIVE = uuid(22);
    private static final UUID INVITE_OTHER = uuid(23);
    private static final UUID INVITE_STALE_INVITER = uuid(24);
    private static final UUID INVITE_MEMBER_AS_INVITER = uuid(25);
    private static final UUID INVITE_MEMBER_AS_INVITEE = uuid(26);
    private static final UUID INVITE_UNRELATED = uuid(27);

    private static final long NOW = 1_000_000L;
    private static final long INVITE_CREATED = NOW - 1_000L;
    private static final long INVITE_EXPIRES = NOW + 60_000L;
    private static final GroupRules.Limits LIMITS = new GroupRules.Limits(4, 8, false, 60_000L);

    /** Real sequence source: the first join of a group starts at 1. */
    private final Map<UUID, Long> sequenceCursor = new HashMap<>();
    /** Real name lookup with a deterministic fallback for unregistered players. */
    private final Map<UUID, String> playerNames = new HashMap<>();

    // ==== create ====

    @Test
    void createRefusesInvalidNamesWithoutChangingState() {
        GroupFileStore.Snapshot empty = emptySnapshot();
        GroupRules.Context ctx = context(NOW);
        for (String bad : List.of("has space", "tab\there", "#channel", "abcdefghijklm", "br[ack]")) {
            GroupRules.Outcome outcome = GroupRules.evaluate(empty,
                request(GroupRules.CREATE, null, null, null, bad), actor(OWNER), ctx);
            assertEquals("e33chat.group.bad_name", outcome.messageKey(), bad);
            assertEquals(List.of(GroupRules.MAX_NAME_LEN), outcome.messageArgs());
            assertFalse(outcome.accepted(), bad);
            assertFalse(outcome.changed(), bad);
            assertSame(empty, outcome.state(), bad);
        }
        assertTrue(empty.groups().isEmpty());
    }

    @Test
    void createRefusesDuplicateLiveName() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.CREATE, null, null, null, "Guild"), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.exists", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void createOperatorOnlyGateRefusesNonOperatorAndAllowsOperator() {
        GroupRules.Limits opOnly = new GroupRules.Limits(4, 8, true, 60_000L);
        GroupFileStore.Snapshot empty = emptySnapshot();

        GroupRules.Outcome refused = GroupRules.evaluate(empty,
            request(GroupRules.CREATE, null, null, null, "Guild"), actor(OWNER), context(opOnly, NOW));
        assertEquals("e33chat.group.op_only", refused.messageKey());
        assertFalse(refused.accepted());
        assertSame(empty, refused.state());

        GroupRules.Outcome allowed = GroupRules.evaluate(empty,
            request(GroupRules.CREATE, null, null, null, "Guild"), operator(OPERATOR), context(opOnly, NOW));
        assertEquals("e33chat.group.created", allowed.messageKey());
        assertTrue(allowed.accepted());
        assertTrue(allowed.changed());
        assertEquals(1, allowed.state().groups().size());
    }

    @Test
    void createRefusesAtGroupCap() {
        GroupRules.Limits capped = new GroupRules.Limits(1, 8, false, 60_000L);
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.CREATE, null, null, null, "Other"), actor(OWNER), context(capped, NOW));
        assertEquals("e33chat.group.limit", outcome.messageKey());
        assertEquals(List.of(1), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void createRegistersOwnerMemberAndJoinSequence() {
        GroupFileStore.Snapshot empty = emptySnapshot();
        GroupRules.Outcome outcome = GroupRules.evaluate(empty,
            request(GroupRules.CREATE, null, null, null, "Guild"), actor(OWNER), context(NOW));

        assertEquals("e33chat.group.created", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        assertEquals(1, outcome.state().groups().size());
        UUID id = outcome.state().groups().keySet().iterator().next();
        GroupFileStore.GroupRecord created = outcome.state().groups().get(id);
        assertEquals(id, created.identity().id());
        assertEquals("Guild", created.identity().displayName());
        assertEquals(OWNER, created.access().ownerId());
        assertTrue(sequenceCursor.containsKey(id), "create must allocate a join sequence");
        assertEquals(Map.of(OWNER, sequenceCursor.get(id)), created.access().visibleFromSequence());
        assertEquals(Set.of(), created.access().legacyHistoryReaders());
        assertTrue(outcome.state().invitations().isEmpty());
    }

    // ==== invite ====

    @Test
    void inviteRequiresMembership() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.INVITE, GROUP, INVITEE, null, ""), actor(STRANGER), context(NOW));
        assertEquals("e33chat.group.not_member_short", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void inviteRequiresTarget() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.INVITE, GROUP, GroupRules.NO_SUBJECT, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.no_target", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void inviteRefusesExistingMember() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        playerNames.put(OWNER, "Steve");
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.INVITE, GROUP, OWNER, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.invitee_member", outcome.messageKey());
        assertEquals(List.of("Steve"), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void inviteRefusesFullGroup() {
        GroupRules.Limits tiny = new GroupRules.Limits(4, 2, false, 60_000L);
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 2L), Set.of())), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.INVITE, GROUP, INVITEE, null, ""), actor(OWNER), context(tiny, NOW));
        assertEquals("e33chat.group.full", outcome.messageKey());
        assertEquals(List.of("Guild", 2), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void repeatedInviteIsIdempotent() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.INVITE, GROUP, INVITEE, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.already_invited", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertFalse(outcome.changed());
        assertSame(state, outcome.state());
        assertEquals(Set.of(INVITE), outcome.state().invitations().keySet());
    }

    @Test
    void successfulInviteAddsOneInvitationWithConfiguredLifetime() {
        GroupRules.Limits limits = new GroupRules.Limits(4, 8, false, 45_000L);
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        playerNames.put(INVITEE, "Alex");

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.INVITE, GROUP, INVITEE, null, ""), actor(OWNER), context(limits, NOW));

        assertEquals("e33chat.group.invited", outcome.messageKey());
        assertEquals(List.of("Alex", "Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        assertEquals(1, outcome.state().invitations().size());
        GroupInvitation added = outcome.state().invitations().values().iterator().next();
        assertEquals(GROUP, added.groupId());
        assertEquals(OWNER, added.inviterId());
        assertEquals(INVITEE, added.inviteeId());
        assertEquals(NOW, added.createdAtMs());
        assertEquals(NOW + 45_000L, added.expiresAtMs());
        assertFalse(outcome.state().groups().get(GROUP).access().isMember(INVITEE));
    }

    @Test
    void inviteReplacesAnExpiredPreviousInvitation() {
        GroupRules.Limits limits = new GroupRules.Limits(4, 8, false, 45_000L);
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE_EXPIRED, invitation(INVITE_EXPIRED, GROUP, OWNER, INVITEE, NOW - 10_000L, NOW - 1L)));

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.INVITE, GROUP, INVITEE, null, ""), actor(OWNER), context(limits, NOW));

        assertEquals("e33chat.group.invited", outcome.messageKey());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        assertEquals(1, outcome.state().invitations().size());
        GroupInvitation replaced = outcome.state().invitations().values().iterator().next();
        assertNotEquals(INVITE_EXPIRED, replaced.id());
        assertEquals(GROUP, replaced.groupId());
        assertEquals(INVITEE, replaced.inviteeId());
        assertEquals(NOW, replaced.createdAtMs());
        assertEquals(NOW + 45_000L, replaced.expiresAtMs());
    }

    // ==== accept ====

    @Test
    void acceptRequiresTheAddressedInvitee() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome otherPlayer = GroupRules.evaluate(state,
            request(GroupRules.ACCEPT, null, null, INVITE, ""), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.invite_gone", otherPlayer.messageKey());
        assertFalse(otherPlayer.accepted());
        assertSame(state, otherPlayer.state());

        GroupRules.Outcome unknownId = GroupRules.evaluate(state,
            request(GroupRules.ACCEPT, null, null, uuid(99), ""), actor(INVITEE), context(NOW));
        assertEquals("e33chat.group.invite_gone", unknownId.messageKey());
        assertSame(state, unknownId.state());
    }

    @Test
    void acceptRemovesExpiredInvitation() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE_EXPIRED, invitation(INVITE_EXPIRED, GROUP, OWNER, INVITEE, NOW - 10_000L, NOW - 1L)));

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.ACCEPT, null, null, INVITE_EXPIRED, ""), actor(INVITEE), context(NOW));

        assertEquals("e33chat.group.invite_expired", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertTrue(outcome.changed());
        assertTrue(outcome.state().invitations().isEmpty());
        assertFalse(outcome.state().groups().get(GROUP).access().isMember(INVITEE));
    }

    @Test
    void acceptRemovesInvitationWhenInviterLeft() {
        // The inviter is no longer a member (the group is owned by somebody else).
        // evaluate()'s pre-purge drops such an invitation; the pre-purge snapshot still
        // tells it apart from an expired one, so the outcome is inviter_left.
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", MEMBER, Map.of(MEMBER, 1L), Set.of())),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.ACCEPT, null, null, INVITE, ""), actor(INVITEE), context(NOW));

        assertEquals("e33chat.group.inviter_left", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertTrue(outcome.changed());
        assertTrue(outcome.state().invitations().isEmpty());
        assertFalse(outcome.state().groups().get(GROUP).access().isMember(INVITEE));
    }

    @Test
    void acceptRefusesFullGroupAndKeepsInvitation() {
        GroupRules.Limits tiny = new GroupRules.Limits(4, 2, false, 60_000L);
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 2L), Set.of())),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.ACCEPT, null, null, INVITE, ""), actor(INVITEE), context(tiny, NOW));

        assertEquals("e33chat.group.full", outcome.messageKey());
        assertEquals(List.of("Guild", 2), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertEquals(Set.of(INVITE), outcome.state().invitations().keySet());
        assertFalse(outcome.state().groups().get(GROUP).access().isMember(INVITEE));
    }

    @Test
    void acceptAddsMemberAtJoinSequenceWithoutLegacyGrant() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 4L), Set.of(OWNER))),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));
        sequenceCursor.put(GROUP, 7L);

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.ACCEPT, null, null, INVITE, ""), actor(INVITEE), context(NOW));

        assertEquals("e33chat.group.joined", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        GroupFileStore.GroupRecord joined = outcome.state().groups().get(GROUP);
        assertEquals(Map.of(OWNER, 1L, MEMBER, 4L, INVITEE, 8L), joined.access().visibleFromSequence());
        assertEquals(Set.of(OWNER), joined.access().legacyHistoryReaders());
        assertFalse(joined.access().canReadLegacy(INVITEE));
        assertTrue(outcome.state().invitations().isEmpty());
        assertEquals(8L, sequenceCursor.get(GROUP));
    }

    @Test
    void acceptRefusesWhenTheConnectionCannotReceiveGroupData() {
        // Sixth precondition: a peer that never completed the groups handshake cannot
        // render group content, so ACCEPT must refuse AND keep the invitation live for
        // a retry once the peer updates (rather than mint a silent mute member).
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.ACCEPT, null, null, INVITE, ""), actor(INVITEE),
            contextRefusingReceivers(LIMITS, NOW));

        assertEquals("e33chat.group.action_upgrade", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertFalse(outcome.changed());
        assertSame(state, outcome.state());
        assertEquals(Set.of(INVITE), outcome.state().invitations().keySet());
        assertFalse(outcome.state().groups().get(GROUP).access().isMember(INVITEE));
    }

    // ==== reject / revoke ====

    @Test
    void rejectOnlyByAddressee() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome otherPlayer = GroupRules.evaluate(state,
            request(GroupRules.REJECT, null, null, INVITE, ""), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.invite_gone", otherPlayer.messageKey());
        assertFalse(otherPlayer.accepted());
        assertSame(state, otherPlayer.state());

        GroupRules.Outcome unknownId = GroupRules.evaluate(state,
            request(GroupRules.REJECT, null, null, uuid(99), ""), actor(INVITEE), context(NOW));
        assertEquals("e33chat.group.invite_gone", unknownId.messageKey());
        assertSame(state, unknownId.state());
    }

    @Test
    void rejectRemovesInvitation() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.REJECT, null, null, INVITE, ""), actor(INVITEE), context(NOW));
        assertEquals("e33chat.group.rejected", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        assertTrue(outcome.state().invitations().isEmpty());
    }

    @Test
    void rejectRemovesInvitationWhenInviterLeft() {
        // Mirror of the ACCEPT case: the pre-purge dropped the invitation because its
        // inviter left, and the pre-purge snapshot reports that reason.
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", MEMBER, Map.of(MEMBER, 1L), Set.of())),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.REJECT, null, null, INVITE, ""), actor(INVITEE), context(NOW));

        assertEquals("e33chat.group.inviter_left", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertTrue(outcome.changed());
        assertTrue(outcome.state().invitations().isEmpty());
    }

    @Test
    void revokeOnlyByInviter() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome stranger = GroupRules.evaluate(state,
            request(GroupRules.REVOKE, null, null, INVITE, ""), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.invite_gone", stranger.messageKey());
        assertFalse(stranger.accepted());
        assertSame(state, stranger.state());

        GroupRules.Outcome addressee = GroupRules.evaluate(state,
            request(GroupRules.REVOKE, null, null, INVITE, ""), actor(INVITEE), context(NOW));
        assertEquals("e33chat.group.invite_gone", addressee.messageKey());
        assertSame(state, addressee.state());
    }

    @Test
    void revokeRemovesInvitation() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));
        playerNames.put(INVITEE, "Alex");
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.REVOKE, null, null, INVITE, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.revoked", outcome.messageKey());
        assertEquals(List.of("Alex", "Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        assertTrue(outcome.state().invitations().isEmpty());
    }

    // ==== leave ====

    @Test
    void leaveBySoleOwnerDisbandsTheGroup() {
        // Nobody to hand the group to: leaving as the only member dissolves it.
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.LEAVE, GROUP, null, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.deleted", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        assertTrue(outcome.state().groups().isEmpty());
    }

    @Test
    void leaveRefusesOwnerWithOtherMembers() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 2L), Set.of())), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.LEAVE, GROUP, null, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.owner_must_transfer", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void leaveRefusesNonMember() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.LEAVE, GROUP, null, null, ""), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.not_member_short", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void leaveRemovesMembershipAndPendingInvitations() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 2L), Set.of(OWNER, MEMBER))),
            Map.of(INVITE_MEMBER_AS_INVITER,
                       invitation(INVITE_MEMBER_AS_INVITER, GROUP, MEMBER, INVITEE, INVITE_CREATED, INVITE_EXPIRES),
                   INVITE_MEMBER_AS_INVITEE,
                       invitation(INVITE_MEMBER_AS_INVITEE, GROUP, OWNER, MEMBER, INVITE_CREATED, INVITE_EXPIRES),
                   INVITE_UNRELATED,
                       invitation(INVITE_UNRELATED, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.LEAVE, GROUP, null, null, ""), actor(MEMBER), context(NOW));

        assertEquals("e33chat.group.left", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        assertEquals(Map.of(OWNER, 1L), outcome.state().groups().get(GROUP).access().visibleFromSequence());
        assertEquals(Set.of(OWNER), outcome.state().groups().get(GROUP).access().legacyHistoryReaders());
        assertEquals(Set.of(INVITE_UNRELATED), outcome.state().invitations().keySet());
    }

    // ==== rename ====

    @Test
    void renameRequiresOwnership() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 2L), Set.of())), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.RENAME, GROUP, null, null, "Guild2"), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.owner_only", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void renameRefusesTakenName() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, owned(GROUP, "Guild", OWNER), OTHER_GROUP, owned(OTHER_GROUP, "Raiders", MEMBER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.RENAME, GROUP, null, null, "Raiders"), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.exists", outcome.messageKey());
        assertEquals(List.of("Raiders"), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void renameRefusesInvalidName() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.RENAME, GROUP, null, null, "bad name"), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.bad_name", outcome.messageKey());
        assertEquals(List.of(GroupRules.MAX_NAME_LEN), outcome.messageArgs());
        assertSame(state, outcome.state());
    }

    @Test
    void renameKeepsIdentityAndAccess() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 3L), Set.of(OWNER))), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.RENAME, GROUP, null, null, "Guild2"), actor(OWNER), context(NOW));

        assertEquals("e33chat.group.renamed", outcome.messageKey());
        assertEquals(List.of("Guild2"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertEquals(Set.of(GROUP), outcome.state().groups().keySet());
        GroupFileStore.GroupRecord renamed = outcome.state().groups().get(GROUP);
        assertEquals(GROUP, renamed.identity().id());
        assertEquals("Guild2", renamed.identity().displayName());
        assertEquals(Map.of(OWNER, 1L, MEMBER, 3L), renamed.access().visibleFromSequence());
        assertEquals(Set.of(OWNER), renamed.access().legacyHistoryReaders());
    }

    // ==== kick ====

    @Test
    void kickRequiresOwnership() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 2L), Set.of())), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.KICK, GROUP, OWNER, null, ""), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.owner_only", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void kickRefusesSelf() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.KICK, GROUP, OWNER, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.kick_self", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void kickRefusesNonMember() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        UUID ghost = uuid(99);
        playerNames.put(ghost, "Ghost");
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.KICK, GROUP, ghost, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.target_not_member", outcome.messageKey());
        assertEquals(List.of("Ghost"), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void kickRemovesMemberAndTheirInvitations() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER,
                Map.of(OWNER, 1L, MEMBER, 2L, STRANGER, 3L), Set.of(OWNER, MEMBER))),
            Map.of(INVITE_MEMBER_AS_INVITER,
                       invitation(INVITE_MEMBER_AS_INVITER, GROUP, MEMBER, INVITEE, INVITE_CREATED, INVITE_EXPIRES),
                   INVITE_UNRELATED,
                       invitation(INVITE_UNRELATED, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));
        playerNames.put(MEMBER, "Bob");

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.KICK, GROUP, MEMBER, null, ""), actor(OWNER), context(NOW));

        assertEquals("e33chat.group.kicked", outcome.messageKey());
        assertEquals(List.of("Bob", "Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        GroupFileStore.GroupRecord after = outcome.state().groups().get(GROUP);
        assertEquals(Map.of(OWNER, 1L, STRANGER, 3L), after.access().visibleFromSequence());
        assertEquals(Set.of(OWNER), after.access().legacyHistoryReaders());
        assertEquals(Set.of(INVITE_UNRELATED), outcome.state().invitations().keySet());
    }

    // ==== transfer ====

    @Test
    void transferRequiresOwnership() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 2L), Set.of())), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.TRANSFER_OWNER, GROUP, MEMBER, null, ""), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.owner_only", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void transferRefusesSelf() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.TRANSFER_OWNER, GROUP, OWNER, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.already_owner", outcome.messageKey());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void transferRefusesNonMember() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());
        UUID ghost = uuid(99);
        playerNames.put(ghost, "Ghost");
        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.TRANSFER_OWNER, GROUP, ghost, null, ""), actor(OWNER), context(NOW));
        assertEquals("e33chat.group.target_not_member", outcome.messageKey());
        assertEquals(List.of("Ghost"), outcome.messageArgs());
        assertFalse(outcome.accepted());
        assertSame(state, outcome.state());
    }

    @Test
    void transferMovesOwnershipAndKeepsFormerOwnerAsMember() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 2L), Set.of(OWNER, MEMBER))),
            Map.of());
        playerNames.put(MEMBER, "Bob");

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.TRANSFER_OWNER, GROUP, MEMBER, null, ""), actor(OWNER), context(NOW));

        assertEquals("e33chat.group.transferred", outcome.messageKey());
        assertEquals(List.of("Guild", "Bob"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        GroupAccess access = outcome.state().groups().get(GROUP).access();
        assertEquals(MEMBER, access.ownerId());
        assertTrue(access.isMember(OWNER));
        assertTrue(access.canLeave(OWNER));
        assertEquals(Map.of(OWNER, 1L, MEMBER, 2L), access.visibleFromSequence());
    }

    // ==== disband ====

    @Test
    void disbandRequiresOwnershipUnlessOperator() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());

        GroupRules.Outcome refused = GroupRules.evaluate(state,
            request(GroupRules.DISBAND, GROUP, null, null, ""), actor(MEMBER), context(NOW));
        assertEquals("e33chat.group.not_owner", refused.messageKey());
        assertEquals(List.of("Guild"), refused.messageArgs());
        assertFalse(refused.accepted());
        assertSame(state, refused.state());

        GroupRules.Outcome rescued = GroupRules.evaluate(state,
            request(GroupRules.DISBAND, GROUP, null, null, ""), operator(OPERATOR), context(NOW));
        assertEquals("e33chat.group.deleted", rescued.messageKey());
        assertTrue(rescued.accepted());
        assertTrue(rescued.changed());
        assertTrue(rescued.state().groups().isEmpty());
    }

    @Test
    void disbandRemovesGroupAndInvitations() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE, invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome outcome = GroupRules.evaluate(state,
            request(GroupRules.DISBAND, GROUP, null, null, ""), actor(OWNER), context(NOW));

        assertEquals("e33chat.group.deleted", outcome.messageKey());
        assertEquals(List.of("Guild"), outcome.messageArgs());
        assertTrue(outcome.accepted());
        assertTrue(outcome.changed());
        assertTrue(outcome.state().groups().isEmpty());
        assertTrue(outcome.state().invitations().isEmpty());
    }

    // ==== expiry ====

    @Test
    void purgeExpiredDropsOnlyDeadInvitations() {
        GroupFileStore.GroupRecord group = owned(GROUP, "Guild", OWNER);
        UUID goneInviter = uuid(99);
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, group),
            Map.of(INVITE_EXPIRED,
                       invitation(INVITE_EXPIRED, GROUP, OWNER, INVITEE, NOW - 10_000L, NOW - 1L),
                   INVITE_STALE_INVITER,
                       invitation(INVITE_STALE_INVITER, GROUP, goneInviter, INVITEE,
                           INVITE_CREATED, INVITE_EXPIRES),
                   INVITE_LIVE,
                       invitation(INVITE_LIVE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome purged = GroupRules.purgeExpired(state, NOW);

        assertTrue(purged.changed());
        assertFalse(purged.accepted());
        assertNull(purged.messageKey());
        assertEquals(Set.of(INVITE_LIVE), purged.state().invitations().keySet());
        assertEquals(Set.of(GROUP), purged.state().groups().keySet());

        GroupFileStore.Snapshot liveOnly = snapshot(Map.of(GROUP, group),
            Map.of(INVITE_LIVE, invitation(INVITE_LIVE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));
        GroupRules.Outcome untouched = GroupRules.purgeExpired(liveOnly, NOW);
        assertFalse(untouched.changed());
        assertSame(liveOnly, untouched.state());
    }

    @Test
    void evaluatePurgesDeadInvitationsBeforeRefusing() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)),
            Map.of(INVITE_EXPIRED,
                       invitation(INVITE_EXPIRED, GROUP, OWNER, INVITEE, NOW - 10_000L, NOW - 1L),
                   INVITE_LIVE,
                       invitation(INVITE_LIVE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        GroupRules.Outcome refused = GroupRules.evaluate(state,
            request(GroupRules.INVITE, GROUP, STRANGER, null, ""), actor(MEMBER), context(NOW));

        assertEquals("e33chat.group.not_member_short", refused.messageKey());
        assertFalse(refused.accepted());
        assertTrue(refused.changed());
        assertEquals(Set.of(INVITE_LIVE), refused.state().invitations().keySet());
    }

    // ==== identity across name reuse ====

    @Test
    void reusingDisbandedNameYieldsADistinctGroup() {
        GroupRules.Context ctx = context(NOW);

        GroupRules.Outcome createdA = GroupRules.evaluate(emptySnapshot(),
            request(GroupRules.CREATE, null, null, null, "Guild"), actor(OWNER), ctx);
        UUID idA = createdA.state().groups().keySet().iterator().next();

        GroupRules.Outcome invited = GroupRules.evaluate(createdA.state(),
            request(GroupRules.INVITE, idA, INVITEE, null, ""), actor(OWNER), ctx);
        assertEquals(1, invited.state().invitations().size());

        GroupRules.Outcome disbanded = GroupRules.evaluate(invited.state(),
            request(GroupRules.DISBAND, idA, null, null, ""), actor(OWNER), ctx);
        assertTrue(disbanded.accepted());
        assertTrue(disbanded.state().groups().isEmpty());
        assertTrue(disbanded.state().invitations().isEmpty());

        GroupRules.Outcome createdB = GroupRules.evaluate(disbanded.state(),
            request(GroupRules.CREATE, null, null, null, "Guild"), actor(OWNER), ctx);
        UUID idB = createdB.state().groups().keySet().iterator().next();

        assertNotEquals(idA, idB);
        assertFalse(createdB.state().groups().containsKey(idA));
        assertEquals(1, createdB.state().groups().size());
        assertEquals(Map.of(OWNER, sequenceCursor.get(idB)),
            createdB.state().groups().get(idB).access().visibleFromSequence());
        assertTrue(createdB.state().groups().get(idB).access().legacyHistoryReaders().isEmpty());
        assertTrue(createdB.state().invitations().isEmpty());
    }

    // ==== invitation projection ====

    @Test
    void invitationsForReturnsOnlyRecipientLiveInvitationsWithCurrentName() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, owned(GROUP, "Guild", OWNER), OTHER_GROUP, owned(OTHER_GROUP, "Raiders", MEMBER)),
            Map.of(INVITE,
                       invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES),
                   INVITE_OTHER,
                       invitation(INVITE_OTHER, OTHER_GROUP, MEMBER, STRANGER, INVITE_CREATED, INVITE_EXPIRES)));

        List<GroupInvitationSummary> forInvitee = GroupRules.invitationsFor(state, INVITEE, NOW);
        assertEquals(1, forInvitee.size());
        assertEquals(new GroupInvitationSummary(INVITE, GROUP, "Guild", OWNER, INVITE_CREATED, INVITE_EXPIRES),
            forInvitee.get(0));

        assertEquals(INVITE_OTHER, GroupRules.invitationsFor(state, STRANGER, NOW).get(0).id());
        assertTrue(GroupRules.invitationsFor(state, null, NOW).isEmpty());

        GroupRules.Outcome renamed = GroupRules.evaluate(state,
            request(GroupRules.RENAME, GROUP, null, null, "Renamed"), actor(OWNER), context(NOW));
        assertEquals("Renamed", GroupRules.invitationsFor(renamed.state(), INVITEE, NOW).get(0).groupName());
    }

    @Test
    void invitationsForHidesExpiredAndInviterLeft() {
        GroupFileStore.GroupRecord group = record(GROUP, "Guild", MEMBER, Map.of(MEMBER, 1L), Set.of());
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, group),
            Map.of(INVITE,
                       invitation(INVITE, GROUP, OWNER, INVITEE, INVITE_CREATED, INVITE_EXPIRES),
                   INVITE_EXPIRED,
                       invitation(INVITE_EXPIRED, GROUP, MEMBER, INVITEE, NOW - 10_000L, NOW - 1L),
                   INVITE_LIVE,
                       invitation(INVITE_LIVE, GROUP, MEMBER, INVITEE, INVITE_CREATED, INVITE_EXPIRES)));

        List<GroupInvitationSummary> summaries = GroupRules.invitationsFor(state, INVITEE, NOW);
        assertEquals(1, summaries.size());
        assertEquals(INVITE_LIVE, summaries.get(0).id());
    }

    // ==== read predicates ====

    @Test
    void canReadSequenceHonoursTheJoinBoundary() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, record(GROUP, "Guild", OWNER, Map.of(OWNER, 1L, MEMBER, 5L), Set.of())), Map.of());

        assertFalse(GroupRules.canReadSequence(state, GROUP, MEMBER, 4L));
        assertTrue(GroupRules.canReadSequence(state, GROUP, MEMBER, 5L));
        assertTrue(GroupRules.canReadSequence(state, GROUP, MEMBER, 6L));
        assertFalse(GroupRules.canReadSequence(state, GROUP, STRANGER, 5L));
        assertFalse(GroupRules.canReadSequence(state, OTHER_GROUP, MEMBER, 5L));
        assertFalse(GroupRules.canReadSequence(state, GROUP, null, 5L));
    }

    @Test
    void membershipAndOwnershipPredicates() {
        GroupFileStore.Snapshot state = snapshot(Map.of(GROUP, owned(GROUP, "Guild", OWNER)), Map.of());

        assertTrue(GroupRules.isOwner(state, GROUP, OWNER));
        assertFalse(GroupRules.isOwner(state, GROUP, MEMBER));
        assertFalse(GroupRules.isOwner(state, OTHER_GROUP, OWNER));
        assertFalse(GroupRules.isOwner(state, GROUP, null));

        assertTrue(GroupRules.isMember(state, GROUP, OWNER));
        assertFalse(GroupRules.isMember(state, GROUP, STRANGER));
        assertFalse(GroupRules.isMember(state, OTHER_GROUP, OWNER));
        assertFalse(GroupRules.isMember(state, GROUP, null));
    }

    @Test
    void groupByNameResolvesOnlyKnownUniqueNames() {
        GroupFileStore.Snapshot state = snapshot(
            Map.of(GROUP, owned(GROUP, "Guild", OWNER), OTHER_GROUP, owned(OTHER_GROUP, "Raiders", MEMBER)), Map.of());

        assertEquals(GROUP, GroupRules.groupByName(state, "Guild").identity().id());
        assertEquals(OTHER_GROUP, GroupRules.groupByName(state, "Raiders").identity().id());
        assertNull(GroupRules.groupByName(state, "nope"));
        assertNull(GroupRules.groupByName(state, null));
    }

    // ==== frozen action contract (M4.1) ====

    @Test
    void actionCodesAreFrozenAndOrdered() {
        assertEquals(0, GroupRules.CREATE);
        assertEquals(1, GroupRules.INVITE);
        assertEquals(2, GroupRules.ACCEPT);
        assertEquals(3, GroupRules.REJECT);
        assertEquals(4, GroupRules.REVOKE);
        assertEquals(5, GroupRules.LEAVE);
        assertEquals(6, GroupRules.RENAME);
        assertEquals(7, GroupRules.KICK);
        assertEquals(8, GroupRules.TRANSFER_OWNER);
        assertEquals(9, GroupRules.DISBAND);
        assertEquals(new UUID(0L, 0L), GroupRules.NO_SUBJECT, "unused UUID fields are the all-zero UUID");
    }

    @Test
    void requestNormalizesUnusedFieldsAndRejectsUnknownCodes() {
        GroupRules.Request normalized = request(GroupRules.CREATE, null, null, null, null);
        assertEquals(GroupRules.NO_SUBJECT, normalized.groupId());
        assertEquals(GroupRules.NO_SUBJECT, normalized.subjectId());
        assertEquals(GroupRules.NO_SUBJECT, normalized.invitationId());
        assertEquals("", normalized.value(), "an unused value is the empty string");

        assertThrows(IllegalArgumentException.class, () -> request(-1, null, null, null, ""));
        assertThrows(IllegalArgumentException.class, () -> request(10, null, null, null, ""));
    }

    @Test
    void everyActionCodeIsDispatchedAndRefusedForUnknownFields() {
        // A well-formed request for each frozen code must route to a real action (never
        // an unhandled default): each one returns an outcome with a message key rather
        // than throwing, except CREATE which succeeds.
        GroupRules.Context ctx = context(NOW);
        for (int action = GroupRules.CREATE; action <= GroupRules.DISBAND; action++) {
            GroupRules.Outcome outcome = GroupRules.evaluate(emptySnapshot(),
                request(action, null, null, null, "Guild"), actor(OWNER), ctx);
            if (action == GroupRules.CREATE) {
                assertTrue(outcome.accepted(), "CREATE on empty state succeeds");
            } else {
                assertNotNull(outcome.messageKey(), "action " + action + " reports a reason");
                assertFalse(outcome.accepted(), "action " + action + " has nothing to act on");
            }
        }
    }

    // ==== fixtures ====

    private GroupRules.Context context(long nowMs) {
        return context(LIMITS, nowMs);
    }

    private GroupRules.Context context(GroupRules.Limits limits, long nowMs) {
        return new GroupRules.Context(limits,
            groupId -> sequenceCursor.merge(groupId, 1L, Long::sum),
            playerId -> playerNames.getOrDefault(playerId, playerId.toString()),
            playerId -> true,
            nowMs);
    }

    /** A context whose ACCEPT receiver gate refuses everyone (an un-ACKed client). */
    private GroupRules.Context contextRefusingReceivers(GroupRules.Limits limits, long nowMs) {
        return new GroupRules.Context(limits,
            groupId -> sequenceCursor.merge(groupId, 1L, Long::sum),
            playerId -> playerNames.getOrDefault(playerId, playerId.toString()),
            playerId -> false,
            nowMs);
    }

    private static GroupRules.Request request(int action, UUID groupId, UUID subjectId, UUID invitationId,
                                              String value) {
        return new GroupRules.Request(action, groupId, subjectId, invitationId, value);
    }

    private static GroupRules.Actor actor(UUID id) {
        return new GroupRules.Actor(id, false);
    }

    private static GroupRules.Actor operator(UUID id) {
        return new GroupRules.Actor(id, true);
    }

    private static GroupFileStore.Snapshot emptySnapshot() {
        return new GroupFileStore.Snapshot(Map.of(), Map.of());
    }

    private static GroupFileStore.Snapshot snapshot(Map<UUID, GroupFileStore.GroupRecord> groups,
                                                    Map<UUID, GroupInvitation> invitations) {
        return new GroupFileStore.Snapshot(groups, invitations);
    }

    private static GroupFileStore.GroupRecord record(UUID id, String name, UUID owner,
                                                     Map<UUID, Long> starts, Set<UUID> legacy) {
        return new GroupFileStore.GroupRecord(new GroupIdentity(id, name), new GroupAccess(owner, starts, legacy));
    }

    private static GroupFileStore.GroupRecord owned(UUID id, String name, UUID owner) {
        return record(id, name, owner, Map.of(owner, 1L), Set.of());
    }

    private static GroupInvitation invitation(UUID id, UUID groupId, UUID inviterId, UUID inviteeId,
                                              long createdAtMs, long expiresAtMs) {
        return new GroupInvitation(id, groupId, inviterId, inviteeId, createdAtMs, expiresAtMs);
    }

    private static UUID uuid(long least) {
        return new UUID(0L, least);
    }
}
