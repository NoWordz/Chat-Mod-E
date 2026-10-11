package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.config.ChatServerConfig;
import com.niuqu.chatbubble.group.GroupAccess;
import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupFeedback;
import com.niuqu.chatbubble.group.GroupFileStore;
import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import com.niuqu.chatbubble.group.GroupHistoryStore;
import com.niuqu.chatbubble.group.GroupInvitation;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import com.niuqu.chatbubble.group.GroupRules;
import com.niuqu.chatbubble.network.GroupNetworkHandler;
import com.niuqu.chatbubble.packets.GroupListPayload;
import com.niuqu.chatbubble.server.ChatServerListener.QuotePending;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * NeoForge server adapter for the 2.5.0 group backend.
 *
 * <p>The lifecycle rules themselves live in {@link GroupRules} (platform-free,
 * one implementation for all three loaders); this class owns only the parts that
 * need the live server: the per-world durable snapshot, the recipient-scoped
 * directory/page projections, the media seams and the v2 fan-out.
 *
 * <p>Authority model: groups are identified by a stable UUID, an invitation must
 * be accepted before anyone joins, and every mutation is
 * validate → compute the complete snapshot → persist it → publish. A failed
 * write leaves both the in-memory state and the clients untouched.
 */
public final class GroupManager {

    /** One name bound for client command suggestions and server validation. */
    public static final int MAX_NAME_LEN = GroupRules.MAX_NAME_LEN;
    /** v2 group wire version this server speaks. */
    public static final int PROTOCOL_VERSION = 1;
    /** Hard page ceiling; matches {@link GroupHistoryStore} and the shared page DTO. */
    static final int MAX_PAGE = 100;
    /** Commands bypass vanilla chat spam kicks — keep a cheap per-player floor. */
    static final long SAY_COOLDOWN_MS = 500;
    /** Invitation-expiry sweep cadence in server ticks (30 s): bounded, no per-tick scan. */
    static final int EXPIRY_SWEEP_TICKS = 600;
    private static final int MAX_CONTENT = 1024;
    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;
    private static final String FILE_NAME = "e33chat-groups.json";
    private static final String HISTORY_DIR = "e33chat-group-history";

    /** The one authoritative roster/invitation snapshot; replaced only after a write succeeds. */
    private static GroupFileStore.Snapshot state = new GroupFileStore.Snapshot(Map.of(), Map.of());
    /**
     * Connections that completed the {@code e33chat:groups} handshake. This set is
     * also the gate {@link #sendDirectory(ServerPlayer)} checks first, so it is the
     * single thing standing between a logged-in client and a group directory — the
     * handshake test asserts this exact set rather than a copy. Package-visible so
     * that test can drive the real transition and read it back.
     */
    static final Set<UUID> groupV2Clients = new LinkedHashSet<>();
    private static final Map<UUID, Long> lastSay = new HashMap<>();
    private static GroupFileStore groupFileStore;
    private static GroupHistoryStore groupHistoryStore;
    /** True when a roster file we could not read also could not be moved aside, so the
     *  only copy of recoverable data must not be overwritten by a later save. */
    private static boolean rosterWriteBlocked;
    private static MinecraftServer boundServer;
    private static int sweepCountdown = EXPIRY_SWEEP_TICKS;

    private GroupManager() {}

    // ==== Pure validation (delegates to the shared rules) ====

    /** Names must survive the vanilla "[name] <player> text" line and not start with '#'. */
    public static boolean isValidGroupName(String name) {
        return GroupRules.isValidName(name);
    }

    /** Split the {@code /e33chat group msg <group> <text>} rest argument. Group
     *  references never contain whitespace, so the first space separates them from
     *  the text. Returns {@code {reference, text}}. */
    public static String[] splitSay(String rest) {
        String s = rest == null ? "" : rest.trim();
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return new String[]{s.substring(0, i), s.substring(i + 1).trim()};
            }
        }
        return new String[]{s, ""};
    }

    // ==== Read-only projections (commands, tests, media authorization) ====

    /** Read-only snapshot of the durable roster; the caller must not mutate. */
    public static Map<UUID, GroupFileStore.GroupRecord> snapshot() {
        return state.groups();
    }

    public static boolean exists(String name) {
        return GroupRules.groupByName(state, name) != null;
    }

    /** Resolve a command argument to a stable id: a group UUID, or a current display name. */
    public static UUID resolveGroupId(String reference) {
        if (reference == null || reference.isEmpty()) return null;
        try {
            UUID asId = UUID.fromString(reference);
            return state.groups().containsKey(asId) ? asId : null;
        } catch (IllegalArgumentException notAnId) {
            GroupFileStore.GroupRecord record = GroupRules.groupByName(state, reference);
            return record == null ? null : record.identity().id();
        }
    }

    /**
     * Resolve a player reference to a stable id: a UUID string, an online player's
     * current name, or a name the server's profile cache knows. Returns null when no
     * such player is known; the caller owns the localized failure message. This is the
     * single resolver for every entry point — the commands and the v2 wire path both
     * come here, so the two cannot drift apart again.
     */
    public static UUID resolvePlayerId(MinecraftServer server, String nameOrId) {
        if (server == null || nameOrId == null || nameOrId.isEmpty()) return null;
        try {
            return UUID.fromString(nameOrId);
        } catch (IllegalArgumentException notAnId) {
            // fall through to the name lookups
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(nameOrId);
        if (online != null) return online.getUUID();
        return server.getProfileCache().get(nameOrId).map(profile -> profile.getId()).orElse(null);
    }

    /** Every group the player is currently in, in roster order. */
    public static List<GroupFileStore.GroupRecord> groupsOf(UUID playerId) {
        List<GroupFileStore.GroupRecord> out = new ArrayList<>();
        if (playerId == null) return out;
        for (GroupFileStore.GroupRecord record : state.groups().values()) {
            if (record.access().isMember(playerId)) out.add(record);
        }
        return out;
    }

    /** The player's live invitations (recipient-scoped, their current group names). */
    public static List<GroupInvitationSummary> invitationsOf(UUID playerId) {
        return GroupRules.invitationsFor(state, playerId, System.currentTimeMillis());
    }

    /** The single live invitation of {@code inviteeId} in that group, or null. */
    public static UUID invitationIdFor(UUID groupId, UUID inviteeId) {
        if (groupId == null || inviteeId == null) return null;
        long now = System.currentTimeMillis();
        for (GroupInvitation invitation : state.invitations().values()) {
            if (invitation.groupId().equals(groupId) && invitation.inviteeId().equals(inviteeId)
                    && !invitation.isExpired(now)) {
                return invitation.id();
            }
        }
        return null;
    }

    /** Media seam: current membership only, regardless of owner status or OP. */
    public static boolean isMemberById(UUID playerId, UUID groupId) {
        if (playerId == null || groupId == null || !ChatServerConfig.GROUPS_ENABLED.get()) return false;
        return GroupRules.isMember(state, groupId, playerId);
    }

    /**
     * Media seam: a member may read a sequence inside their own window, bounded above
     * by the highest sequence the server ever allocated for that group. Owners and
     * operators get no extra window.
     */
    public static boolean canReadGroupSequence(UUID playerId, UUID groupId, long sequence) {
        if (sequence < 1 || !ChatServerConfig.GROUPS_ENABLED.get()) return false;
        if (!GroupRules.canReadSequence(state, groupId, playerId, sequence)) return false;
        GroupHistoryStore store = groupHistoryStore;
        return store != null && sequence <= store.lastSequence(groupId);
    }

    /** Production login predicate: only a current member still in the migrated legacy cohort. */
    public static boolean canReadLegacy(UUID playerId, String groupName) {
        GroupFileStore.GroupRecord record = GroupRules.groupByName(state, groupName);
        return record != null && playerId != null && record.access().canReadLegacy(playerId);
    }

    // ==== Network entry points (the actor is always the receiving player) ====

    /** Loads the per-world state on first use; safe to call on every login. */
    public static void onPlayerLogin(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server != null) ensureLoaded(server);
    }

    /**
     * The pre-2.5 main-channel hello. A peer without the groups channel is a
     * 2.4.x client: it gets no v1 group list any more, only the explicit
     * "groups unavailable" state so its UI hides the group entry instead of
     * showing one that can never load. A v2 peer learns everything from the
     * groups-channel handshake.
     */
    public static void onClientHello(ServerPlayer player) {
        if (player == null || hasGroupsChannel(player)) return;
        // NeoForge aborts a send whose payload the peer never declared, so the
        // legacy list is gated on the real channel set too.
        if (!player.connection.hasChannel(GroupListPayload.TYPE)) return;
        PacketDistributor.sendToPlayer(player, new GroupListPayload(false, List.of(), List.of(), List.of()));
    }

    /** Whether that connection declared it can receive the v2 group channel. */
    private static boolean hasGroupsChannel(ServerPlayer player) {
        return player.connection.hasChannel(com.niuqu.chatbubble.packets.GroupDirectoryPayload.TYPE);
    }

    /**
     * {@code e33chat:groups} handshake. Only protocol {@link #PROTOCOL_VERSION} is
     * accepted; anything else is refused with that protocol echoed back and gets no
     * directory. The ACK carries the current {@code groups_enabled} state, and that
     * state — not the protocol number — decides whether a directory is sent, so a
     * runtime re-enable can push to an already-known peer.
     */
    public static void onGroupProtocolHello(ServerPlayer player, int protocol) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server != null) ensureLoaded(server);
        if (!registerV2Client(player.getUUID(), protocol)) {
            // A newer dialect is refused: the peer keeps its own state and gets no
            // directory. It is not a 2.4.x client, so no v1 fallback applies.
            GroupNetworkHandler.sendAck(player, protocol, false);
            return;
        }
        boolean enabled = ChatServerConfig.GROUPS_ENABLED.get();
        GroupNetworkHandler.sendAck(player, PROTOCOL_VERSION, enabled);
        if (enabled) sendDirectory(player);
    }

    /**
     * The HELLO's membership step, isolated from the live-server sends so the
     * handshake contract is unit-testable: an accepted protocol registers the
     * connection in {@link #groupV2Clients} (the exact set {@link
     * #sendDirectory(ServerPlayer)} gates on), any other offer forgets it, and a
     * null id is ignored. Returns whether the offered dialect was accepted.
     */
    static boolean registerV2Client(UUID playerId, int protocol) {
        if (playerId == null) return false;
        if (protocol != PROTOCOL_VERSION) {
            groupV2Clients.remove(playerId);
            return false;
        }
        groupV2Clients.add(playerId);
        return true;
    }

    /**
     * Re-affirms the dialect and pushes the recipient-scoped directory to one
     * connection: only the groups that connection's player is in, plus the live
     * invitations addressed to them. Empty while {@code groups_enabled=false}.
     */
    public static void sendDirectory(ServerPlayer player) {
        if (player == null) return;
        if (!groupV2Clients.contains(player.getUUID())) return;
        boolean enabled = ChatServerConfig.GROUPS_ENABLED.get();
        GroupNetworkHandler.sendAck(player, PROTOCOL_VERSION, enabled);
        if (!enabled) return;   // a refused ACK already revokes the client's directory
        UUID recipient = player.getUUID();
        GroupNetworkHandler.sendDirectory(player, buildDirectory(recipient),
            GroupRules.invitationsFor(state, recipient, System.currentTimeMillis()));
    }

    /** Refresh every known v2 connection after a mutation, a toggle or an expiry sweep. */
    public static void broadcastDirectory() {
        MinecraftServer server = boundServer;
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendDirectory(p);
        }
    }

    /** The actor is always the real player; no identity ever comes off the wire. */
    public static void handleHistoryRequest(ServerPlayer player, UUID groupId, long after, int limit) {
        if (player == null || groupId == null) return;
        MinecraftServer server = player.getServer();
        if (server != null) ensureLoaded(server);
        // A connection that never completed the handshake gets the same empty page
        // as an exhausted member: nothing here reveals whether the group exists.
        GroupHistoryPage page = groupV2Clients.contains(player.getUUID())
            ? buildHistoryPage(player.getUUID(), groupId, after, limit)
            : emptyPage(groupId, after);
        GroupNetworkHandler.sendHistoryPage(player, page);
    }

    /**
     * A v2 lifecycle action. Unknown action codes (a newer client) are ignored, and
     * the request is validated and applied by the shared rules; nothing is published
     * unless the snapshot reached disk.
     */
    public static void handleActionV2(ServerPlayer player, int action, UUID groupId, UUID subjectId,
                                      UUID invitationId, String value) {
        if (player == null || action < GroupRules.CREATE || action > GroupRules.DISBAND) return;
        applyAction(player, action, groupId, subjectId, invitationId, value);
    }

    /**
     * A pre-2.5 main-channel action. It is never a public join: the v1 wire carries
     * no invitation, so the request is explicitly refused. A peer that speaks the
     * groups channel is told its client UI is stale; a 2.4.x peer is told the v1
     * group features are unavailable here (a key its own language file has).
     */
    public static void handleLegacyAction(ServerPlayer player, int action, String groupName) {
        if (player == null) return;
        // The capability decides the wording, not the handshake timing: a peer that
        // declared the groups channel only has a stale UI, while a 2.4.x peer cannot
        // run these actions at all (and its own language file has that key).
        if (hasGroupsChannel(player)) {
            fail(player, "e33chat.group.action_upgrade");
        } else {
            fail(player, "e33chat.group.disabled");
        }
    }

    /** Validates, persists and publishes one action for the authenticated player. */
    public static void applyAction(ServerPlayer player, int action, UUID groupId, UUID subjectId,
                                   UUID invitationId, String value) {
        if (!ChatServerConfig.GROUPS_ENABLED.get()) { fail(player, "e33chat.group.disabled"); return; }
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ensureLoaded(server);
        if (groupHistoryStore == null && (action == GroupRules.CREATE || action == GroupRules.ACCEPT)) {
            // Both actions record the actor's readable-history window from the durable
            // history store; without it no truthful sequence exists, so refuse the
            // action instead of persisting a fabricated window that would hand a later
            // joiner the whole backlog.
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Refusing group action {} from {}: the history store is unavailable",
                action, player.getName().getString());
            fail(player, "e33chat.group.save_failed");
            return;
        }
        // The GUI panel sends an invite as (groupId, no subjectId, invitee name in
        // `value`); resolve that name here so the wire path behaves exactly like the
        // command path. A genuinely empty target still reaches the rules and reports
        // no_target there.
        if (action == GroupRules.INVITE && (subjectId == null || GroupRules.NO_SUBJECT.equals(subjectId))) {
            String inviteeName = value == null ? "" : value.trim();
            if (!inviteeName.isEmpty()) {
                UUID resolved = resolvePlayerId(server, inviteeName);
                if (resolved == null) {
                    fail(player, "e33chat.group.player_unknown", inviteeName);
                    return;
                }
                subjectId = resolved;
            }
        }
        GroupRules.Outcome outcome;
        try {
            outcome = GroupRules.evaluate(state,
                new GroupRules.Request(action, groupId, subjectId, invitationId, value),
                new GroupRules.Actor(player.getUUID(), player.hasPermissions(2)), rulesContext());
        } catch (IllegalArgumentException malformedRequest) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Rejecting malformed group action from {}", player.getName().getString(),
                malformedRequest);
            return;
        }
        GroupFileStore.Snapshot next = outcome.state();
        if (outcome.changed() && !save(next)) {
            fail(player, "e33chat.group.save_failed");
            return;
        }
        boolean groupExisted = state.groups().containsKey(groupId);
        state = next;
        if (outcome.messageKey() != null) feedback(player, outcome.messageKey(), outcome.messageArgs());
        if (!outcome.changed()) return;
        // Key the cleanup on the resulting state, not the request code: a sole owner's LEAVE
        // is turned into a disband by GroupRules.leave, and its history/media must go too.
        if (outcome.accepted() && groupExisted && outcome.state().groups().get(groupId) == null) dropGroupData(groupId);
        broadcastDirectory();
    }

    // ==== Group messages ====

    /**
     * One group message from a member: durable row first, fan-out second. A message
     * the server cannot keep must not be broadcast as if it had been delivered, and
     * only connections that proved the v2 capability receive private group content.
     */
    public static void say(ServerPlayer sender, UUID groupId, String content) {
        if (!ChatServerConfig.GROUPS_ENABLED.get()) { fail(sender, "e33chat.group.disabled"); return; }
        MinecraftServer server = sender.getServer();
        if (server == null) return;
        ensureLoaded(server);
        GroupFileStore.GroupRecord group = groupId == null ? null : state.groups().get(groupId);
        if (group == null) { fail(sender, "e33chat.group.gone"); return; }
        String groupName = group.identity().displayName();
        if (!group.access().isMember(sender.getUUID())) {
            fail(sender, "e33chat.group.not_member_short", groupName);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSay.get(sender.getUUID());
        if (last != null && now - last < SAY_COOLDOWN_MS) { fail(sender, "e33chat.group.cooldown"); return; }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) { fail(sender, "e33chat.group.empty"); return; }
        if (text.length() > MAX_CONTENT) text = text.substring(0, MAX_CONTENT);

        // Media authorization first (M9): a hosted media reference that belongs to
        // another group is refused before anything is stored or fanned out. The
        // check reads the durable roster, not the wire.
        if (!MediaService.validateGroupMessage(sender.getUUID(), groupId, text,
                groupMediaStore())) {
            fail(sender, "e33chat.group.media_denied");
            return;
        }
        lastSay.put(sender.getUUID(), now);

        String senderName = sender.getName().getString();
        QuotePending quote = ChatServerListener.consumeQuote(sender.getUUID());
        GroupHistoryEntry entry = appendGroupHistory(groupId, sender.getUUID(), senderName, text, now,
            quote != null ? quote.quotedContent() : null,
            quote != null ? quote.quotedSenderName() : null,
            groupHistoryStore);
        if (entry == null) { fail(sender, "e33chat.group.send_failed"); return; }
        // Bind the referenced upload to this row's sequence AFTER the row is
        // durable (M9). A failure must never widen access: the file stays PENDING
        // (readable only by its uploader or a current member), and the message
        // itself is already committed.
        try {
            MediaService.onGroupMessageCommitted(entry, groupMediaStore());
        } catch (IOException bindFailed) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Failed to bind group media for group {}", groupId, bindFailed);
        }

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!groupV2Clients.contains(p.getUUID())) continue;
            if (!group.access().isMember(p.getUUID())) continue;
            GroupNetworkHandler.sendChat(p, entry);
        }
    }

    /**
     * Appends one durable group row before any fan-out. Returns {@code null} (after a
     * warn log) when the row cannot be written, so the caller reports a localized
     * failure and no client ever sees a message the server did not keep.
     */
    static GroupHistoryEntry appendGroupHistory(UUID groupId, UUID senderId, String senderName, String content,
                                                long time, String replyContent, String replySender,
                                                GroupHistoryStore store) {
        if (store == null) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Dropping group message for {}: durable history store is unavailable", groupId);
            return null;
        }
        try {
            return store.append(groupId, senderId, senderName, content, time, replyContent, replySender);
        } catch (IOException ex) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Failed to persist group message for {}", groupId, ex);
            return null;
        }
    }

    // ==== Directory and history projections ====

    /** Recipient-scoped directory: only the groups this player is a member of. */
    public static List<GroupDirectoryEntry> buildDirectory(UUID recipient) {
        return directoryFor(state.groups().values(), recipient, groupHistoryStore,
            ChatServerConfig.GROUPS_ENABLED.get());
    }

    /**
     * Pure directory projection. Package-private so tests can drive it with real
     * roster/durable-store fixtures; {@code groupsEnabled=false} yields an empty
     * directory so a served directory can never leak memberships while the feature
     * is off.
     */
    static List<GroupDirectoryEntry> directoryFor(Collection<GroupFileStore.GroupRecord> roster, UUID recipient,
                                                  GroupHistoryStore store, boolean groupsEnabled) {
        if (!groupsEnabled || recipient == null) return List.of();
        if (store == null) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Projecting a group directory without durable history: every group is"
                    + " reported with no retained history (fail closed)");
        }
        List<GroupDirectoryEntry> directory = new ArrayList<>();
        for (GroupFileStore.GroupRecord record : roster) {
            GroupAccess access = record.access();
            Long visibleFrom = access.visibleFromSequence().get(recipient);
            if (visibleFrom == null) continue;
            long latest = store == null ? 0L : store.lastSequence(record.identity().id());
            long retainedFrom = store == null ? 1L : store.retainedFromSequence(record.identity().id());
            try {
                directory.add(new GroupDirectoryEntry(record.identity(), access.ownerId(),
                    new ArrayList<>(access.visibleFromSequence().keySet()), visibleFrom,
                    access.legacyHistoryReaders().contains(recipient), latest, Math.max(retainedFrom, 1L)));
            } catch (IllegalArgumentException inconsistentMemberState) {
                // One inconsistent group must not cost the recipient the rest of the
                // directory; the log keeps the cause visible.
                com.mojang.logging.LogUtils.getLogger().warn(
                    "[e33chat] Skipping group {} in the directory for {}", record.identity().displayName(),
                    recipient, inconsistentMemberState);
            }
        }
        return directory;
    }

    /**
     * Recipient-authorized bounded history page. Non-members and unknown groups get
     * the same-shaped empty page (cursor echoed, {@code hasMore=false}) so the latest
     * sequence is never leaked; so does every caller while {@code groups_enabled=false}.
     */
    public static GroupHistoryPage buildHistoryPage(UUID recipient, UUID groupId, long after, int limit) {
        if (groupId == null) return null;
        GroupFileStore.GroupRecord record = state.groups().get(groupId);
        if (record == null) return emptyPage(groupId, after);
        GroupHistoryStore store = groupHistoryStore;
        if (store == null) {
            // Unreachable for a caller that must not learn group existence: the group is
            // already known, so refusing loudly here leaks nothing the caller did not send.
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Refusing a history page for {}: the history store is unavailable", groupId);
            return emptyPage(groupId, after);
        }
        return pageFor(record.access(), recipient, groupId, after, limit, store,
            ChatServerConfig.GROUPS_ENABLED.get());
    }

    /**
     * Page math over real durable state. The cursor is clamped at 0 ({@code Long.MAX_VALUE}
     * yields an empty page — no {@code +1} overflow); the limit is clamped to
     * [0, {@value #MAX_PAGE}]; rows below the recipient's join sequence or the retained
     * window are never read; {@code hasMore} comes from an actual next-row lookup
     * rather than a sequence comparison.
     */
    static GroupHistoryPage pageFor(GroupAccess access, UUID recipient, UUID groupId,
                                    long after, int limit, GroupHistoryStore store, boolean groupsEnabled) {
        long cursor = Math.max(after, 0L);
        if (!groupsEnabled) return emptyPage(groupId, cursor);
        int capped = Math.min(Math.max(limit, 0), MAX_PAGE);
        if (capped == 0 || store == null) return emptyPage(groupId, cursor);
        Long visibleFrom = access.visibleFromSequence().get(recipient);
        if (visibleFrom == null) return emptyPage(groupId, cursor);
        try {
            long lowerBound = Math.max(visibleFrom, store.retainedFromSequence(groupId));
            List<GroupHistoryEntry> entries = store.pageAfter(groupId, Math.max(cursor, lowerBound - 1), capped);
            if (entries.isEmpty()) return emptyPage(groupId, cursor);
            long next = entries.get(entries.size() - 1).sequence();
            boolean hasMore = !store.pageAfter(groupId, next, 1).isEmpty();
            return new GroupHistoryPage(groupId, next, hasMore, entries);
        } catch (IOException ex) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Failed to read group history for {}", groupId, ex);
            return emptyPage(groupId, cursor);
        }
    }

    static GroupHistoryPage emptyPage(UUID groupId, long cursor) {
        return new GroupHistoryPage(Objects.requireNonNull(groupId, "groupId"),
            Math.max(cursor, 0L), false, List.of());
    }

    // ==== Lifecycle ====

    public static void onPlayerLoggedOut(UUID id) {
        if (id == null) return;
        groupV2Clients.remove(id);
        lastSay.remove(id);
    }

    /**
     * Bounded background maintenance: once per {@link #EXPIRY_SWEEP_TICKS} ticks the
     * expired (and otherwise dead) invitations are dropped and the persisted state
     * plus the directories are refreshed. Nothing here trusts a client.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        ensureLoaded(server);
        if (--sweepCountdown > 0) return;
        sweepCountdown = EXPIRY_SWEEP_TICKS;
        GroupRules.Outcome sweep = GroupRules.purgeExpired(state, System.currentTimeMillis());
        if (!sweep.changed() || !save(sweep.state())) return;   // keep the old state; the next sweep retries
        state = sweep.state();
        broadcastDirectory();
    }

    public static void onServerStopping(MinecraftServer server) {
        if (server != null) save(state);
        if (groupHistoryStore != null) {
            try {
                groupHistoryStore.close();
            } catch (IOException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("[e33chat] Failed to close group history store", ex);
            }
        }
        state = new GroupFileStore.Snapshot(Map.of(), Map.of());
        groupV2Clients.clear();
        lastSay.clear();
        groupFileStore = null;
        groupHistoryStore = null;
        boundServer = null;
        sweepCountdown = EXPIRY_SWEEP_TICKS;
    }

    /** Drops a disbanded group's durable history and its pending media bindings. */
    private static void dropGroupData(UUID groupId) {
        GroupHistoryStore store = groupHistoryStore;
        if (store == null) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Group history for {} was not deleted now (the history store is"
                    + " unavailable); the next load's retainOnly reaps it", groupId);
        } else {
            try {
                store.delete(groupId);
            } catch (IOException ex) {
                com.mojang.logging.LogUtils.getLogger().warn(
                    "[e33chat] Failed to delete group history for {}", groupId, ex);
            }
        }
        // Drop this disbanded group's hosted media. Deleting the bytes is the
        // safety property: a failure must never widen access, and the disband
        // itself is already durable, so every group-media check now denies.
        try {
            MediaService.onGroupDeleted(groupId, groupMediaStore());
        } catch (RuntimeException mediaFailure) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Failed to drop media bindings of disbanded group {}", groupId, mediaFailure);
        }
    }

    /** The live media store, or null before the server has one (headless tests).
     *  All group-media seams go through here so a missing store fails closed. */
    private static DiskMediaStore groupMediaStore() {
        try {
            return ChatServerListener.mediaStore();
        } catch (RuntimeException noServer) {
            return null;
        }
    }

    // ==== Persistence ====

    private static void ensureLoaded(MinecraftServer server) {
        if (boundServer == server) return;
        load(server);
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve(FILE_NAME);
    }

    private static void load(MinecraftServer server) {
        boundServer = server;
        Path groupsFile = file(server);
        groupFileStore = new GroupFileStore(groupsFile);
        rosterWriteBlocked = false;
        boolean rosterLoaded;
        try {
            state = admissible(groupFileStore.load());
            rosterLoaded = true;
        } catch (IOException ex) {
            // The roster file has nothing to do with the history store, so a failure
            // here is handled on its own. Preserve the unreadable file first so a later
            // save() cannot overwrite the only copy of recoverable data; if even that
            // fails, block all roster writes until an operator fixes the file.
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Failed to load the group roster; the file is kept for recovery", ex);
            rosterWriteBlocked = !backupBrokenFile(groupsFile);
            state = new GroupFileStore.Snapshot(Map.of(), Map.of());
            rosterLoaded = false;
        }
        // A roster we could not read is moved aside as "<name>.broken-<ts>" and this or a
        // later session may mint a replacement roster that is missing groups which still
        // own valid history. While any such sidecar survives, the reap below must stay off:
        // the replacement looks trustworthy (rosterLoaded == true) and would otherwise
        // delete the retained rows of every group it does not know. The guard is recomputed
        // from the directory on every load, so an operator who restores the roster from the
        // sidecar and deletes it re-enables the reap with no extra bookkeeping.
        boolean preservedBrokenRoster = hasPreservedBrokenRoster(groupsFile);
        GroupHistoryStore history = null;
        try {
            history = new GroupHistoryStore(groupsFile.getParent().resolve(HISTORY_DIR),
                ChatServerConfig.GROUP_HISTORY_RETENTION_DAYS.get(),
                ChatServerConfig.GROUP_HISTORY_MAX_MESSAGES.get(),
                ChatServerConfig.GROUP_HISTORY_MAX_MIB.get() * 1024L * 1024L);
            // Reap stale history only against a trustworthy roster: an empty roster
            // from a failed load would otherwise purge every group's retained rows, and
            // a roster minted after such a failure (see preservedBrokenRoster) would reap
            // every group it does not know about, including ones the sidecar still holds.
            if (rosterLoaded && !preservedBrokenRoster) history.retainOnly(state.groups().keySet());
        } catch (IOException ex) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Group history is unavailable this session; group chat and history"
                    + " reads are refused, the group roster itself is unaffected", ex);
            history = null;
        }
        groupHistoryStore = history;
    }

    /** Keeps a roster file we could not read on disk for recovery instead of letting
     *  the next save() overwrite the only copy (mirrors the pre-2.5.0 convention).
     *  @return false when the file could not be moved aside, so the caller blocks saves. */
    private static boolean backupBrokenFile(Path file) {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            java.nio.file.Files.move(file, backup);
            com.mojang.logging.LogUtils.getLogger().warn("[e33chat] Kept unreadable group roster as {}", backup);
            return true;
        } catch (Exception ex) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Could not move the unreadable group roster aside; group saves are blocked", ex);
            return false;
        }
    }

    /** True while a roster kept aside by {@link #backupBrokenFile} is still on disk. The
     *  destructive history reap stays off for as long as that sidecar exists, because the
     *  roster the next save writes may lack groups whose retained rows are still wanted.
     *  Never deletes or renames the sidecar: a human restoring it removes the file, which
     *  re-enables the reap. Fails safe (true) when the directory cannot be listed. */
    private static boolean hasPreservedBrokenRoster(Path groupsFile) {
        try (var siblings = java.nio.file.Files.newDirectoryStream(
                groupsFile.getParent(), FILE_NAME + ".broken-*")) {
            return siblings.iterator().hasNext();
        } catch (IOException | java.nio.file.DirectoryIteratorException ex) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Could not check for a preserved group roster; the history reap stays off", ex);
            return true;
        }
    }

    /**
     * Drops rows whose display name the current rules cannot serve (an old v1 name
     * with a space, say) together with the invitations that pointed at them, instead
     * of letting one bad legacy row fail the whole load.
     */
    private static GroupFileStore.Snapshot admissible(GroupFileStore.Snapshot loaded) {
        LinkedHashMap<UUID, GroupFileStore.GroupRecord> groups = new LinkedHashMap<>();
        for (var entry : loaded.groups().entrySet()) {
            if (!GroupRules.isValidName(entry.getValue().identity().displayName())) continue;
            groups.put(entry.getKey(), entry.getValue());
        }
        if (groups.size() == loaded.groups().size()) return loaded;
        LinkedHashMap<UUID, GroupInvitation> invitations = new LinkedHashMap<>();
        for (var entry : loaded.invitations().entrySet()) {
            if (groups.containsKey(entry.getValue().groupId())) invitations.put(entry.getKey(), entry.getValue());
        }
        com.mojang.logging.LogUtils.getLogger().warn(
            "[e33chat] Dropped {} group rows whose names the current rules cannot serve",
            loaded.groups().size() - groups.size());
        return new GroupFileStore.Snapshot(groups, invitations);
    }

    /** @return false when the write failed; callers report the failure instead of
     *  acknowledging an operation that never reached disk. */
    private static boolean save(GroupFileStore.Snapshot next) {
        GroupFileStore store = groupFileStore;
        if (store == null) return false;
        if (rosterWriteBlocked) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Refusing to save groups: the previous roster file is unreadable and"
                    + " could not be preserved");
            return false;
        }
        try {
            store.save(next);
            return true;
        } catch (IOException | IllegalArgumentException ex) {
            com.mojang.logging.LogUtils.getLogger().warn("[e33chat] Failed to save groups", ex);
            return false;
        }
    }

    // ==== Shared-rule plumbing ====

    private static GroupRules.Context rulesContext() {
        return new GroupRules.Context(limits(), GroupManager::nextVisibleSequence,
            GroupManager::playerName, groupV2Clients::contains, System.currentTimeMillis());
    }

    private static GroupRules.Limits limits() {
        return new GroupRules.Limits(ChatServerConfig.GROUP_MAX_COUNT.get(),
            ChatServerConfig.GROUP_MAX_MEMBERS.get(), ChatServerConfig.GROUP_CREATE_OP_ONLY.get(),
            ChatServerConfig.GROUP_INVITE_LIFETIME_DAYS.get() * MILLIS_PER_DAY);
    }

    private static long nextVisibleSequence(UUID groupId) {
        GroupHistoryStore store = groupHistoryStore;
        if (store == null) {
            // applyAction refuses the sequence-allocating actions while the store is
            // unavailable, so this is unreachable in practice: fail fast rather than
            // fabricate a start sequence and persist it as a truthful window.
            throw new IllegalStateException("group history store is unavailable");
        }
        return store.lastSequence(groupId) + 1L;
    }

    /** An online player's name, or the short UUID when they are offline. */
    public static String playerName(UUID playerId) {
        MinecraftServer server = boundServer;
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(playerId);
            if (online != null) return online.getName().getString();
        }
        return playerId == null ? "" : playerId.toString().substring(0, 8);
    }

    // ==== feedback helpers ====

    // Group action results — success or refusal — go to the action-bar overlay, never
    // the chat log. The client tab filter (GroupChannelState.filterMessages) drops
    // every system row that is not in the active tab, so a refusal delivered as chat
    // is invisible exactly while the actor sits in the group tab the action came from
    // (audit GroupsServerLogic finding 2, M4.3). The lane is decided by the shared
    // {@link GroupFeedback} contract, so "overlay" here is the same fact the
    // platform-free test pins.
    static boolean overlayLane(String key) {
        return GroupFeedback.survivesTabFilter(GroupFeedback.laneFor(key));
    }

    private static void feedback(ServerPlayer p, String key, List<Object> args) {
        p.displayClientMessage(Component.translatable(key, args.toArray()), overlayLane(key));
    }

    private static void fail(ServerPlayer p, String key, Object... args) {
        p.displayClientMessage(Component.translatable(key, args), overlayLane(key));
    }
}
