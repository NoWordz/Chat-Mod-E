package com.niuqu.chatbubble.group;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Platform-free group lifecycle rules (2.5.0 phase 5).
 *
 * <p>Every mutation has the same shape: validate the request against the current
 * durable state, then compute the complete snapshot that must be persisted. The
 * platform adapter persists that snapshot <em>before</em> it publishes anything;
 * a failed write means the actor gets a localized failure and no state changed.
 * This class holds no Minecraft, no file and no network type, so every consent
 * rule below is testable on its own.
 *
 * <p>Identity is always the stable group UUID; display names are attributes that
 * changes like {@code RENAME} may rewrite. Nothing here resolves a group by name,
 * so a later group reusing a disbanded group's name is a different group with no
 * inherited members, invitations or history.
 */
public final class GroupRules {

    /** 0 create (value = name). */
    public static final int CREATE = 0;
    /** 1 invite (groupId, subjectId = invitee; the invitee's display name in
     *  {@code value} is accepted too and resolved server-side — the GUI panel sends it there). */
    public static final int INVITE = 1;
    /** 2 accept (invitationId). */
    public static final int ACCEPT = 2;
    /** 3 reject (invitationId). */
    public static final int REJECT = 3;
    /** 4 revoke (invitationId). */
    public static final int REVOKE = 4;
    /** 5 leave (groupId). */
    public static final int LEAVE = 5;
    /** 6 rename (groupId, value = new name). */
    public static final int RENAME = 6;
    /** 7 kick (groupId, subjectId = member to remove). */
    public static final int KICK = 7;
    /** 8 transfer ownership (groupId, subjectId = new owner). */
    public static final int TRANSFER_OWNER = 8;
    /** 9 disband (groupId). */
    public static final int DISBAND = 9;

    /** Wire placeholder for an unused UUID field ({@code 00000000-0000-0000-0000-000000000000}). */
    public static final UUID NO_SUBJECT = new UUID(0L, 0L);

    /** Longest accepted display name; the client's command suggestions use the same bound. */
    public static final int MAX_NAME_LEN = 12;

    /**
     * Names must survive the vanilla {@code "[name] <player> text"} line unambiguous,
     * and must not start with {@code '#'} (reserved for the client's pseudo tabs).
     */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LEN) return false;
        if (name.charAt(0) == '#') return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c)) return false;
            if (c == '[' || c == ']' || c == '<' || c == '>' || c == '\u00a7') return false;
        }
        return true;
    }

    /** The authenticated caller: never an identity that arrived over the wire. */
    public record Actor(UUID id, boolean operator) {
        public Actor {
            Objects.requireNonNull(id, "id");
        }
    }

    /** Server configuration the rules must respect. */
    public record Limits(int maxGroups, int maxMembers, boolean createOpOnly, long inviteLifetimeMs) {
        public Limits {
            if (maxGroups < 1 || maxMembers < 2 || inviteLifetimeMs < 1) {
                throw new IllegalArgumentException("invalid group limits");
            }
        }
    }

    /** One wire request; unused fields carry {@link #NO_SUBJECT} / an empty value. */
    public record Request(int action, UUID groupId, UUID subjectId, UUID invitationId, String value) {
        public Request {
            if (action < CREATE || action > DISBAND) {
                throw new IllegalArgumentException("unknown group action " + action);
            }
            groupId = groupId == null ? NO_SUBJECT : groupId;
            subjectId = subjectId == null ? NO_SUBJECT : subjectId;
            invitationId = invitationId == null ? NO_SUBJECT : invitationId;
            value = value == null ? "" : value;
        }
    }

    /** Sequence a member starts reading at: the highest allocated sequence + 1. */
    public interface SequenceSource {
        long nextSequence(UUID groupId);
    }

    /** Display name for a player UUID (an online name; the adapter decides the offline fallback). */
    public interface NameLookup {
        String displayName(UUID playerId);
    }

    /**
     * Whether a connection can actually receive v2 group data. One of the ACCEPT
     * preconditions: a connection that never completed the {@code e33chat:groups}
     * handshake cannot render group content, so letting it join would mint a silent,
     * mute member (audit GroupsServerLogic finding 1). The adapter answers from its
     * live handshake registry; the rule itself stays platform-free.
     */
    public interface ReceiverCheck {
        boolean canReceive(UUID playerId);
    }

    /** Everything a rule needs beyond the durable state and the request. */
    public record Context(Limits limits, SequenceSource sequences, NameLookup names,
                          ReceiverCheck receivers, long nowMs) {
        public Context {
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(sequences, "sequences");
            Objects.requireNonNull(names, "names");
            Objects.requireNonNull(receivers, "receivers");
        }
    }

    /**
     * One evaluated request. {@code state} is always the snapshot the caller must
     * hold; it differs from the input only when {@code changed} is true.
     * {@code accepted} separates "the action happened" from "the action was
     * refused"; {@code messageKey} may be null (background cleanup). A refused
     * action may still be {@code changed} when it also dropped stale invitations.
     */
    public record Outcome(GroupFileStore.Snapshot state, boolean accepted, boolean changed,
                          String messageKey, List<Object> messageArgs) {
        public Outcome {
            Objects.requireNonNull(state, "state");
            messageArgs = List.copyOf(messageArgs);
        }
    }

    private GroupRules() {}

    /** Evaluates one action. Never mutates {@code current}. */
    public static Outcome evaluate(GroupFileStore.Snapshot current, Request request, Actor actor, Context context) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(context, "context");

        Map<UUID, GroupFileStore.GroupRecord> groups = new LinkedHashMap<>(current.groups());
        Map<UUID, GroupInvitation> invitations = new LinkedHashMap<>(current.invitations());
        boolean changed = dropDeadInvitations(groups, invitations, context.nowMs());

        switch (request.action()) {
            case CREATE:
                return create(current, groups, invitations, changed, request, actor, context);
            case INVITE:
                return invite(current, groups, invitations, changed, request, actor, context);
            case ACCEPT:
                return accept(current, groups, invitations, changed, request, actor, context);
            case REJECT:
                return reject(current, groups, invitations, changed, request, actor, context);
            case REVOKE:
                return revoke(current, groups, invitations, changed, request, actor, context);
            case LEAVE:
                return leave(current, groups, invitations, changed, request, actor, context);
            case RENAME:
                return rename(current, groups, invitations, changed, request, actor, context);
            case KICK:
                return kick(current, groups, invitations, changed, request, actor, context);
            case TRANSFER_OWNER:
                return transfer(current, groups, invitations, changed, request, actor, context);
            case DISBAND:
                return disband(current, groups, invitations, changed, request, actor, context);
            default:
                throw new IllegalArgumentException("unknown group action " + request.action());
        }
    }

    /**
     * Drops every invitation that is no longer live: past its expiry, whose group is
     * gone, or whose inviter is no longer a member. Returns whether anything changed.
     */
    public static Outcome purgeExpired(GroupFileStore.Snapshot current, long nowMs) {
        Objects.requireNonNull(current, "current");
        Map<UUID, GroupInvitation> invitations = new LinkedHashMap<>(current.invitations());
        boolean changed = dropDeadInvitations(current.groups(), invitations, nowMs);
        return new Outcome(changed ? new GroupFileStore.Snapshot(current.groups(), invitations) : current,
            false, changed, null, List.of());
    }

    /** Live invitations addressed to one recipient, with their current group names. */
    public static List<GroupInvitationSummary> invitationsFor(GroupFileStore.Snapshot state, UUID recipientId,
                                                              long nowMs) {
        if (recipientId == null) return List.of();
        List<GroupInvitationSummary> out = new ArrayList<>();
        for (GroupInvitation invitation : state.invitations().values()) {
            if (!recipientId.equals(invitation.inviteeId()) || invitation.isExpired(nowMs)) continue;
            GroupFileStore.GroupRecord record = state.groups().get(invitation.groupId());
            if (record == null || !record.access().isMember(invitation.inviterId())) continue;
            out.add(new GroupInvitationSummary(invitation.id(), invitation.groupId(),
                record.identity().displayName(), invitation.inviterId(),
                invitation.createdAtMs(), invitation.expiresAtMs()));
        }
        return out;
    }

    /** True when the group exists and its owner is the owner of exactly that UUID. */
    public static boolean isOwner(GroupFileStore.Snapshot state, UUID groupId, UUID playerId) {
        GroupFileStore.GroupRecord record = groupId == null ? null : state.groups().get(groupId);
        return record != null && playerId != null && record.access().isOwner(playerId);
    }

    /**
     * The member's readable window: the member's inclusive join sequence is the
     * lower bound and the highest allocated sequence the upper bound. Unknown
     * groups and non-members are never readable; ownership grants nothing extra.
     */
    public static boolean canReadSequence(GroupFileStore.Snapshot state, UUID groupId, UUID playerId, long sequence) {
        GroupFileStore.GroupRecord record = groupId == null ? null : state.groups().get(groupId);
        if (record == null || playerId == null) return false;
        Long visibleFrom = record.access().visibleFromSequence().get(playerId);
        return visibleFrom != null && sequence >= visibleFrom;
    }

    /** True when the group exists and the player is one of its current members. */
    public static boolean isMember(GroupFileStore.Snapshot state, UUID groupId, UUID playerId) {
        GroupFileStore.GroupRecord record = groupId == null ? null : state.groups().get(groupId);
        return record != null && playerId != null && record.access().isMember(playerId);
    }

    /** The single group whose display name is {@code name}, or null when none does. */
    public static GroupFileStore.GroupRecord groupByName(GroupFileStore.Snapshot state, String name) {
        if (name == null) return null;
        GroupFileStore.GroupRecord found = null;
        for (GroupFileStore.GroupRecord record : state.groups().values()) {
            if (record.identity().displayName().equals(name)) {
                if (found != null) return null;   // two matches can never be resolved by name
                found = record;
            }
        }
        return found;
    }

    // ==== actions ====

    private static Outcome create(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                  Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                  Actor actor, Context context) {
        String name = request.value().trim();
        if (!isValidName(name)) return finish(current, groups, invitations, changed, false,
            "e33chat.group.bad_name", MAX_NAME_LEN);
        if (taken(groups, name, null)) return finish(current, groups, invitations, changed, false,
            "e33chat.group.exists", name);
        if (context.limits().createOpOnly() && !actor.operator()) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.op_only");
        }
        if (groups.size() >= context.limits().maxGroups()) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.limit",
                context.limits().maxGroups());
        }
        UUID id = UUID.randomUUID();
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>();
        starts.put(actor.id(), context.sequences().nextSequence(id));
        groups.put(id, new GroupFileStore.GroupRecord(new GroupIdentity(id, name),
            new GroupAccess(actor.id(), starts, Set.of())));
        return finish(current, groups, invitations, true, true, "e33chat.group.created", name);
    }

    private static Outcome invite(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                  Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                  Actor actor, Context context) {
        GroupFileStore.GroupRecord record = groups.get(request.groupId());
        if (record == null) return gone(current, groups, invitations, changed);
        String groupName = record.identity().displayName();
        GroupAccess access = record.access();
        if (!access.isMember(actor.id())) {
            return finish(current, groups, invitations, changed, false,
                "e33chat.group.not_member_short", groupName);
        }
        UUID invitee = request.subjectId();
        if (NO_SUBJECT.equals(invitee)) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.no_target");
        }
        if (access.isMember(invitee)) {
            return finish(current, groups, invitations, changed, false,
                "e33chat.group.invitee_member", context.names().displayName(invitee));
        }
        if (access.visibleFromSequence().size() >= context.limits().maxMembers()) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.full",
                groupName, context.limits().maxMembers());
        }
        for (GroupInvitation live : invitations.values()) {
            if (live.groupId().equals(request.groupId()) && live.inviteeId().equals(invitee)) {
                // Idempotent: a second invite never replaces the live one.
                return finish(current, groups, invitations, changed, false,
                    "e33chat.group.already_invited", context.names().displayName(invitee));
            }
        }
        long expiresAt = expiryOf(context.nowMs(), context.limits().inviteLifetimeMs());
        UUID invitationId = UUID.randomUUID();
        invitations.put(invitationId, new GroupInvitation(invitationId, request.groupId(), actor.id(), invitee,
            context.nowMs(), expiresAt));
        return finish(current, groups, invitations, true, true, "e33chat.group.invited",
            context.names().displayName(invitee), groupName);
    }

    private static Outcome accept(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                  Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                  Actor actor, Context context) {
        // The pre-purge guarantees what is left here is live: not expired, its group
        // exists and its inviter is still a member.
        GroupInvitation invitation = invitations.get(request.invitationId());
        GroupFileStore.GroupRecord record = invitation == null ? null : groups.get(invitation.groupId());
        if (invitation == null || record == null || !invitation.inviteeId().equals(actor.id())) {
            return stale(current, groups, invitations, changed, request, actor, context);
        }
        String groupName = record.identity().displayName();
        if (record.access().isMember(actor.id())) {
            invitations.remove(invitation.id());
            return finish(current, groups, invitations, true, false, "e33chat.group.already_in", groupName);
        }
        // Sixth precondition: the accepting connection must have proved it can receive
        // v2 group data. A connection that never acknowledged the groups channel would
        // otherwise become a member who receives nothing (and the invitation would be
        // spent). The invitation survives so the peer may retry after updating.
        if (!context.receivers().canReceive(actor.id())) {
            return finish(current, groups, invitations, changed, false,
                "e33chat.group.action_upgrade");
        }
        if (record.access().visibleFromSequence().size() >= context.limits().maxMembers()) {
            // The invitation survives a full group: the invitee may retry after a kick.
            return finish(current, groups, invitations, changed, false, "e33chat.group.full",
                groupName, context.limits().maxMembers());
        }
        UUID groupId = invitation.groupId();
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>(record.access().visibleFromSequence());
        starts.put(actor.id(), context.sequences().nextSequence(groupId));
        LinkedHashSet<UUID> legacy = new LinkedHashSet<>(record.access().legacyHistoryReaders());
        groups.put(groupId, new GroupFileStore.GroupRecord(record.identity(),
            new GroupAccess(record.access().ownerId(), starts, legacy)));
        invitations.values().removeIf(live -> live.groupId().equals(groupId) && live.inviteeId().equals(actor.id()));
        return finish(current, groups, invitations, true, true, "e33chat.group.joined", groupName);
    }

    /**
     * Why a live-invitation lookup failed. The pre-purge already removed every
     * invitation that expired or lost its group/inviter — that removal is the
     * required effect — so the pre-purge snapshot is what separates those from an
     * id that was never addressed to this actor. A foreign invitation is never
     * explained (the actor only learns that its own id is invalid).
     */
    private static Outcome stale(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                 Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                 Actor actor, Context context) {
        GroupInvitation previous = current.invitations().get(request.invitationId());
        if (previous != null && previous.inviteeId().equals(actor.id())) {
            if (previous.isExpired(context.nowMs())) {
                return finish(current, groups, invitations, changed, false, "e33chat.group.invite_expired");
            }
            return finish(current, groups, invitations, changed, false, "e33chat.group.inviter_left");
        }
        return finish(current, groups, invitations, changed, false, "e33chat.group.invite_gone");
    }

    private static Outcome reject(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                  Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                  Actor actor, Context context) {
        GroupInvitation invitation = invitations.get(request.invitationId());
        GroupFileStore.GroupRecord record = invitation == null ? null : groups.get(invitation.groupId());
        if (invitation == null || record == null || !invitation.inviteeId().equals(actor.id())) {
            return stale(current, groups, invitations, changed, request, actor, context);
        }
        String groupName = record.identity().displayName();
        invitations.remove(invitation.id());
        return finish(current, groups, invitations, true, true, "e33chat.group.rejected", groupName);
    }

    private static Outcome revoke(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                  Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                  Actor actor, Context context) {
        GroupInvitation invitation = invitations.get(request.invitationId());
        if (invitation == null || !invitation.inviterId().equals(actor.id())) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.invite_gone");
        }
        GroupFileStore.GroupRecord record = groups.get(invitation.groupId());
        String groupName = record == null ? "" : record.identity().displayName();
        String inviteeName = context.names().displayName(invitation.inviteeId());
        invitations.remove(invitation.id());
        return finish(current, groups, invitations, true, true, "e33chat.group.revoked", inviteeName, groupName);
    }

    private static Outcome leave(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                 Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                 Actor actor, Context context) {
        GroupFileStore.GroupRecord record = groups.get(request.groupId());
        if (record == null) return gone(current, groups, invitations, changed);
        String groupName = record.identity().displayName();
        GroupAccess access = record.access();
        if (!access.isMember(actor.id())) {
            return finish(current, groups, invitations, changed, false,
                "e33chat.group.not_member_short", groupName);
        }
        if (access.isOwner(actor.id())) {
            // A sole owner leaving is a disband: there is nobody to hand the group to,
            // and an ownerless group would be unreachable for every remaining member.
            if (access.visibleFromSequence().size() <= 1) {
                return disband(current, groups, invitations, changed, request, actor, context);
            }
            // Otherwise the owner must hand the group over or disband it, never walk away.
            return finish(current, groups, invitations, changed, false,
                "e33chat.group.owner_must_transfer", groupName);
        }
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>(access.visibleFromSequence());
        starts.remove(actor.id());
        LinkedHashSet<UUID> legacy = new LinkedHashSet<>(access.legacyHistoryReaders());
        legacy.remove(actor.id());
        UUID groupId = record.identity().id();
        groups.put(groupId, new GroupFileStore.GroupRecord(record.identity(),
            new GroupAccess(access.ownerId(), starts, legacy)));
        // A member who leaves takes their pending invitations with them (as inviter or invitee).
        invitations.values().removeIf(live -> live.groupId().equals(groupId)
            && (live.inviterId().equals(actor.id()) || live.inviteeId().equals(actor.id())));
        return finish(current, groups, invitations, true, true, "e33chat.group.left", groupName);
    }

    private static Outcome rename(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                  Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                  Actor actor, Context context) {
        GroupFileStore.GroupRecord record = groups.get(request.groupId());
        if (record == null) return gone(current, groups, invitations, changed);
        if (!record.access().isOwner(actor.id())) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.owner_only");
        }
        String newName = request.value().trim();
        if (!isValidName(newName)) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.bad_name", MAX_NAME_LEN);
        }
        if (taken(groups, newName, record.identity().id())) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.exists", newName);
        }
        groups.put(record.identity().id(),
            new GroupFileStore.GroupRecord(record.identity().renamed(newName), record.access()));
        return finish(current, groups, invitations, true, true, "e33chat.group.renamed", newName);
    }

    private static Outcome kick(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                Actor actor, Context context) {
        GroupFileStore.GroupRecord record = groups.get(request.groupId());
        if (record == null) return gone(current, groups, invitations, changed);
        String groupName = record.identity().displayName();
        if (!record.access().isOwner(actor.id())) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.owner_only");
        }
        UUID subject = request.subjectId();
        if (NO_SUBJECT.equals(subject)) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.no_target");
        }
        if (subject.equals(actor.id())) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.kick_self");
        }
        if (!record.access().isMember(subject)) {
            return finish(current, groups, invitations, changed, false,
                "e33chat.group.target_not_member", context.names().displayName(subject));
        }
        LinkedHashMap<UUID, Long> starts = new LinkedHashMap<>(record.access().visibleFromSequence());
        starts.remove(subject);
        LinkedHashSet<UUID> legacy = new LinkedHashSet<>(record.access().legacyHistoryReaders());
        legacy.remove(subject);
        UUID groupId = record.identity().id();
        groups.put(groupId, new GroupFileStore.GroupRecord(record.identity(),
            new GroupAccess(record.access().ownerId(), starts, legacy)));
        // A kicked member's pending invitations are invalid with their membership.
        invitations.values().removeIf(live -> live.groupId().equals(groupId) && live.inviterId().equals(subject));
        return finish(current, groups, invitations, true, true, "e33chat.group.kicked",
            context.names().displayName(subject), groupName);
    }

    private static Outcome transfer(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                    Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                    Actor actor, Context context) {
        GroupFileStore.GroupRecord record = groups.get(request.groupId());
        if (record == null) return gone(current, groups, invitations, changed);
        String groupName = record.identity().displayName();
        if (!record.access().isOwner(actor.id())) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.owner_only");
        }
        UUID subject = request.subjectId();
        if (NO_SUBJECT.equals(subject)) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.no_target");
        }
        if (subject.equals(actor.id())) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.already_owner");
        }
        if (!record.access().isMember(subject)) {
            return finish(current, groups, invitations, changed, false,
                "e33chat.group.target_not_member", context.names().displayName(subject));
        }
        groups.put(record.identity().id(), new GroupFileStore.GroupRecord(record.identity(),
            new GroupAccess(subject, record.access().visibleFromSequence(),
                record.access().legacyHistoryReaders())));
        return finish(current, groups, invitations, true, true, "e33chat.group.transferred",
            groupName, context.names().displayName(subject));
    }

    private static Outcome disband(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                   Map<UUID, GroupInvitation> invitations, boolean changed, Request request,
                                   Actor actor, Context context) {
        GroupFileStore.GroupRecord record = groups.get(request.groupId());
        if (record == null) return gone(current, groups, invitations, changed);
        String groupName = record.identity().displayName();
        // The pre-2.5 operator rescue exception: disbanding is the one lifecycle
        // action an operator may perform on a group they do not own.
        if (!record.access().isOwner(actor.id()) && !actor.operator()) {
            return finish(current, groups, invitations, changed, false, "e33chat.group.not_owner", groupName);
        }
        UUID groupId = record.identity().id();
        groups.remove(groupId);
        invitations.values().removeIf(live -> live.groupId().equals(groupId));
        return finish(current, groups, invitations, true, true, "e33chat.group.deleted", groupName);
    }

    // ==== helpers ====

    private static Outcome gone(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                               Map<UUID, GroupInvitation> invitations, boolean changed) {
        return finish(current, groups, invitations, changed, false, "e33chat.group.gone");
    }

    private static Outcome finish(GroupFileStore.Snapshot current, Map<UUID, GroupFileStore.GroupRecord> groups,
                                  Map<UUID, GroupInvitation> invitations, boolean changed, boolean accepted,
                                  String messageKey, Object... messageArgs) {
        GroupFileStore.Snapshot state = changed ? new GroupFileStore.Snapshot(groups, invitations) : current;
        // A null argument would be rejected by List.of and cost the actor the whole
        // action report; a missing display name is not worth that.
        Object[] safeArgs = new Object[messageArgs.length];
        for (int i = 0; i < messageArgs.length; i++) {
            safeArgs[i] = messageArgs[i] == null ? "" : messageArgs[i];
        }
        return new Outcome(state, accepted, changed, messageKey, List.of(safeArgs));
    }

    private static boolean dropDeadInvitations(Map<UUID, GroupFileStore.GroupRecord> groups,
                                              Map<UUID, GroupInvitation> invitations, long nowMs) {
        return invitations.values().removeIf(invitation -> {
            if (invitation.isExpired(nowMs)) return true;
            GroupFileStore.GroupRecord record = groups.get(invitation.groupId());
            return record == null || !record.access().isMember(invitation.inviterId());
        });
    }

    private static boolean taken(Map<UUID, GroupFileStore.GroupRecord> groups, String name, UUID exceptId) {
        for (GroupFileStore.GroupRecord record : groups.values()) {
            if (record.identity().id().equals(exceptId)) continue;
            if (record.identity().displayName().equals(name)) return true;
        }
        return false;
    }

    /** Saturating expiry: a configured lifetime can never overflow into a past timestamp. */
    private static long expiryOf(long nowMs, long lifetimeMs) {
        long expires = nowMs + lifetimeMs;
        return expires < nowMs ? Long.MAX_VALUE : expires;
    }
}
