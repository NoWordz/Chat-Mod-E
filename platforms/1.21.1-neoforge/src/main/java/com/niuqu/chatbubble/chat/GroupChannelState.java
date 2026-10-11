package com.niuqu.chatbubble.chat;

import com.niuqu.chatbubble.logic.ChannelStateMachine;
import com.niuqu.chatbubble.logic.MessageFilter;
import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.group.GroupHistoryPage;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import com.niuqu.chatbubble.store.ChatMessageStore.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side group channel state (2.4.10; v2 since 2.5.0).
 *
 * Tab model: {@link #TAB_ALL} = "全部" (world + system, groups stay in their
 * own tab), {@link #TAB_WORLD} = public player messages, {@link #TAB_SYSTEM} =
 * system messages, otherwise the group name. All pseudo ids start with '#' and group names can never start with '#'
 * (server-side validation), so they can't collide with real groups.
 *
 * The tab strip is only shown once the server sent a group directory
 * ({@code enabled} flag) — singleplayer and vanilla servers hide it entirely.
 *
 * v2 (2.5.0): after the {@code e33chat:groups} handshake the server sends a
 * recipient-scoped directory keyed by the stable group UUID instead of the
 * public v1 list. Everything here that touches v2 is pure state — the packet
 * handlers own the wire, the store owns the JSONL — so the authorization and
 * paging rules stay testable without a Minecraft client.
 */
public final class GroupChannelState {

    public static final String TAB_ALL = "#all";
    public static final String TAB_WORLD = "#world";
    public static final String TAB_SYSTEM = "#system";

    /** v2 group protocol this client speaks. An ACK for anything else is refused. */
    public static final int GROUP_PROTOCOL = 1;

    /** Server-side page bound is 100; the client never asks for more. */
    public static final int PAGE_LIMIT = 100;

    /** Set from the (v1) GroupListPacket.enabled; reset on disconnect. */
    public static volatile boolean enabled;

    /** Directory from the latest GroupListPacket. */
    public static final Set<String> knownGroups = new LinkedHashSet<>();
    public static final Set<String> myGroups = new LinkedHashSet<>();

    /**
     * Tab/whisper authority: the shared, MC-free {@link ChannelStateMachine}. All
     * tab transitions and the leave-group fallback go through it, so the three
     * targets cannot drift on 「世界频道」 leaving a group, whisper-vs-group
     * exclusivity, or the directory fallback.
     */
    private static final ChannelStateMachine CHANNELS = new ChannelStateMachine();

    // ==== v2 negotiation (connection-scoped) ====

    private static volatile boolean v2Acknowledged;
    /**
     * True once any protocol-1 ACK arrived — accepted or refused. A refusal is not
     * "no v2 server": the server proved it speaks v2 and turned the feature off, so
     * the legacy v1 channel must stay ignored instead of silently coming back as
     * permissive, name-grouped client state.
     */
    private static volatile boolean v2ServerKnown;
    /** Recipient-scoped directory keyed by the stable group UUID. */
    private static final Map<UUID, GroupDirectoryEntry> directory = new LinkedHashMap<>();
    /** Last sequence covered by the paging chain, per group. */
    private static final Map<UUID, Long> backfillCursor = new LinkedHashMap<>();
    /** The membership window the current cursor belongs to (max of both floors). */
    private static final Map<UUID, Long> backfillFloor = new LinkedHashMap<>();
    private static final Set<UUID> backfillDone = new LinkedHashSet<>();
    /** Recipient-scoped invitations from the latest v2 directory (2.5.0). */
    private static final List<GroupInvitationSummary> invitations = new ArrayList<>();
    /**
     * Bumped whenever the directory or the negotiation changes. The UI reads it
     * to bind pre-2.5 legacy rows to their grant exactly once per directory, so a
     * per-frame render pass never re-scans the whole history.
     */
    private static volatile long directoryVersion;

    /** One page the caller must transmit on the groups channel. */
    public record PageRequest(UUID groupId, long after, int limit) {}

    /** Rows to store plus the follow-up request, if the chain continues. */
    public record PageOutcome(List<GroupHistoryEntry> accepted, PageRequest next) {
        public PageOutcome {
            accepted = List.copyOf(accepted);
        }
    }

    private GroupChannelState() {}

    public static boolean supported() {
        return enabled;
    }

    public static String active() {
        return enabled ? CHANNELS.active() : null;
    }

    /** The private-chat partner, or null when not in a whisper view. */
    public static String whisperPartner() {
        return enabled ? CHANNELS.whisperPartner() : null;
    }

    /** True while a whisper view owns the message list. */
    public static boolean isWhisper() {
        return enabled && CHANNELS.isWhisper();
    }

    /** True when a real group channel (not a pseudo tab, not a whisper view) is active. */
    public static boolean isGroup() {
        return enabled && CHANNELS.isGroup();
    }

    /**
     * The selected group's stable UUID, or {@code null} for a non-group tab (or
     * when the strip is unavailable). Selection is kept by id, not by label, so a
     * rename never drops the player out of their group.
     */
    public static UUID activeGroupId() {
        if (!enabled) return null;
        return parseUuid(CHANNELS.active());
    }

    /** Select a group by its stable UUID; {@code null} selects 「全部」. */
    public static boolean setActiveGroup(UUID groupId) {
        return setActive(groupId == null ? TAB_ALL : groupId.toString());
    }

    /** Returns true if the tab changed. Also used for the pseudo tabs and, before
     *  the v2 directory arrives, for the legacy name-keyed group tabs. */
    public static boolean setActive(String tab) {
        return CHANNELS.selectTab(tab);
    }

    /** Select a group channel key (ends any whisper view). */
    public static boolean selectGroup(String groupKey) {
        return CHANNELS.selectGroup(groupKey);
    }

    /** Select a player's whisper view (drops any group tab). */
    public static boolean selectWhisper(String partner) {
        return CHANNELS.selectWhisper(partner);
    }

    /** The 「世界频道」 row: leave a group tab to 全部 (never 世界), end a whisper. */
    public static boolean selectWorldRow() {
        return CHANNELS.selectWorldRow();
    }

    /**
     * The v1 public list. Once the server answered our v2 handshake the
     * recipient-scoped directory is authoritative, so a late v1 list (sent by the
     * server on mutations before it saw our ACK, or by the runtime disable path)
     * must neither overwrite it nor — after a refusal — re-enable the
     * name-grouped v1 behaviour the server declined.
     */
    public static void applyLegacyDirectory(boolean serverEnabled, List<String> names,
                                            List<Integer> counts, List<String> mine) {
        if (v2ServerKnown) return;
        enabled = serverEnabled;
        applyDirectory(names, counts, mine);
    }

    public static void applyDirectory(List<String> names, List<Integer> counts, List<String> mine) {
        knownGroups.clear();
        if (names != null) knownGroups.addAll(names);
        myGroups.clear();
        if (mine != null) myGroups.addAll(mine);
        // The active tab must stay meaningful: if the group vanished (left,
        // deleted, disbanded) fall back to "全部" via the shared machine.
        CHANNELS.applyDirectory(validTabs());
    }

    // ==== v2 ====

    /** True once the server accepted our v2 handshake. */
    public static boolean acknowledged() {
        return v2Acknowledged;
    }

    /**
     * True once the server answered our protocol-1 HELLO, accepted or refused.
     * From then on the v1 directory and the v1 live channel are ignored: the
     * server owns group delivery on this connection, so stale v1 data must not
     * resurrect name-grouped behaviour.
     */
    public static boolean v2ServerKnown() {
        return v2ServerKnown;
    }

    /**
     * v2 handshake reply. Only protocol {@link #GROUP_PROTOCOL} is ours — an ACK
     * for another dialect must not touch this connection's state.
     *
     * <p>A refusal revokes everything the handshake granted (directory, paging
     * state, group tab) but keeps the server marked as v2, so a refused connection
     * cannot fall back to the permissive v1 grouping a later v1 list would bring
     * back. A later acceptance restores the negotiated state without a new HELLO,
     * which is what lets a runtime disable → enable recover.
     */
    public static void onAck(int protocol, boolean accepted) {
        if (protocol != GROUP_PROTOCOL) return;
        v2ServerKnown = true;
        if (accepted) {
            v2Acknowledged = true;
            return;
        }
        v2Acknowledged = false;
        enabled = false;
        CHANNELS.reset();
        directory.clear();
        backfillCursor.clear();
        backfillFloor.clear();
        backfillDone.clear();
        invitations.clear();
        directoryVersion++;
    }

    public static GroupDirectoryEntry directoryEntry(UUID groupId) {
        return groupId == null ? null : directory.get(groupId);
    }

    /** Unmodifiable view of the recipient-scoped directory (diagnostics/tests). */
    public static Map<UUID, GroupDirectoryEntry> directory() {
        return Collections.unmodifiableMap(directory);
    }

    /**
     * Replace the directory and return the pages the caller must request. Rows
     * are fetched from the member's own window floor, never from the newest row:
     * starting high would leave a hole under a late live message.
     *
     * <p>When a group's floor moved (leave + rejoin, or retention trimming) the
     * cursor restarts there, because rows below the new floor are no longer ours.
     *
     * <p>{@code receivedInvitations} are the recipient-scoped invitations; they
     * are kept as decoded state for the rail/inbox, not used for paging.
     */
    public static List<PageRequest> applyV2Directory(List<GroupDirectoryEntry> entries,
                                                     List<GroupInvitationSummary> receivedInvitations) {
        if (!v2Acknowledged) return List.of();
        directory.clear();
        if (entries != null) {
            for (GroupDirectoryEntry entry : entries) {
                if (entry != null) directory.put(entry.identity().id(), entry);
            }
        }
        invitations.clear();
        if (receivedInvitations != null) {
            for (GroupInvitationSummary invitation : receivedInvitations) {
                if (invitation != null) invitations.add(invitation);
            }
        }
        knownGroups.clear();
        for (GroupDirectoryEntry entry : directory.values()) {
            knownGroups.add(entry.identity().displayName());
        }
        myGroups.clear();
        myGroups.addAll(knownGroups);
        enabled = true;
        // Keep the selection on the same group: a pre-v2 name tab upgrades to the
        // stable id so a later rename doesn't drop the player out of the group; a
        // group that left the directory falls back to 「全部」.
        if (!isPseudoTab(CHANNELS.active())) {
            String currentKey = CHANNELS.active();
            UUID current = parseUuid(currentKey);
            if (current == null) {
                String upgraded = upgradeLegacyTab(currentKey);
                CHANNELS.selectGroup(upgraded != null ? upgraded : TAB_ALL);
            } else if (!directory.containsKey(current)) {
                CHANNELS.selectGroup(TAB_ALL);
            }
        }
        directoryVersion++;
        // A group we no longer belong to must not keep paging state around.
        backfillCursor.keySet().retainAll(directory.keySet());
        backfillFloor.keySet().retainAll(directory.keySet());
        backfillDone.retainAll(directory.keySet());

        List<PageRequest> requests = new ArrayList<>();
        for (GroupDirectoryEntry entry : directory.values()) {
            UUID groupId = entry.identity().id();
            long floor = Math.max(entry.visibleFromSequence(), entry.retainedFromSequence()) - 1L;
            if (floor < 0) floor = 0;
            Long knownFloor = backfillFloor.get(groupId);
            if (knownFloor == null || knownFloor != floor) {
                backfillFloor.put(groupId, floor);
                backfillCursor.put(groupId, floor);
                backfillDone.remove(groupId);
            } else if (backfillDone.contains(groupId)) {
                continue;
            }
            Long cursor = backfillCursor.get(groupId);
            long after = cursor == null ? floor : Math.max(cursor, floor);
            if (entry.latestSequence() <= after) {
                backfillDone.add(groupId);
                backfillCursor.put(groupId, after);
                continue;
            }
            backfillCursor.put(groupId, after);
            requests.add(new PageRequest(groupId, after, PAGE_LIMIT));
        }
        return requests;
    }

    /**
     * Consume one history page.
     *
     * @return the rows to store and the follow-up request, or {@code null} when
     *         the page is out of scope, stale, or does not advance the cursor —
     *         a rejected page is never retried automatically.
     */
    public static PageOutcome acceptHistoryPage(GroupHistoryPage page) {
        if (!v2Acknowledged || page == null) return null;
        GroupDirectoryEntry entry = directory.get(page.groupId());
        if (entry == null) return null;
        Long cursor = backfillCursor.get(page.groupId());
        if (cursor == null) return null;
        if (page.nextSequence() <= cursor) {
            // Nothing new: either the chain is finished or the page is late.
            backfillDone.add(page.groupId());
            return null;
        }
        List<GroupHistoryEntry> accepted = new ArrayList<>();
        for (GroupHistoryEntry row : page.entries()) {
            if (row.sequence() < entry.visibleFromSequence()) {
                // Rows below our window cannot be ours: the page was built for a
                // previous membership window, so the whole page is discarded and
                // the cursor stays put rather than jumping over unfetched rows.
                return null;
            }
            if (row.sequence() <= cursor) continue;
            accepted.add(row);
        }
        backfillCursor.put(page.groupId(), page.nextSequence());
        if (page.hasMore()) {
            return new PageOutcome(accepted, new PageRequest(page.groupId(), page.nextSequence(), PAGE_LIMIT));
        }
        backfillDone.add(page.groupId());
        return new PageOutcome(accepted, null);
    }

    /** Last sequence the paging chain covers, or null when no backfill ran. */
    public static Long backfillCursor(UUID groupId) {
        return groupId == null ? null : backfillCursor.get(groupId);
    }

    public static boolean backfillComplete(UUID groupId) {
        return groupId != null && backfillDone.contains(groupId);
    }

    // ==== cached-row visibility ====

    /**
     * Whether a cached row may be shown right now.
     *
     * <p>While the server is unknown (no protocol-1 ACK at all) the 2.4.x
     * behaviour stands: group rows group by display name. Once an ACK arrived —
     * even a refusal — the directory is the only authority: a numbered row needs
     * its stable group id in the directory and a sequence inside the current
     * window, and an unnumbered (pre-2.5) row needs the directory's explicit
     * legacy grant, so the local JSONL can never widen what the server authorized.
     */
    public static boolean isVisible(ChatMessage msg) {
        if (msg == null) return false;
        String group = msg.group();
        if (group == null || group.isEmpty()) return true;
        if (!v2ServerKnown) return true;
        UUID groupId = msg.groupId();
        if (groupId != null) {
            GroupDirectoryEntry entry = directory.get(groupId);
            Long sequence = msg.groupSequence();
            return entry != null && sequence != null && sequence >= entry.visibleFromSequence();
        }
        return legacyGranted(group);
    }

    /** An unnumbered row is only ours when exactly one granted group answers for that name. */
    private static boolean legacyGranted(String displayName) {
        return legacyGrantFor(displayName) != null;
    }

    /**
     * The stable id an unnumbered (pre-2.5) row may bind to, or {@code null}.
     * Exactly one current directory group must carry that display name and grant
     * the legacy window; two same-named groups are ambiguous and grant nothing,
     * so a same-name new group can never adopt another group's bound rows.
     */
    public static UUID legacyGrantFor(String displayName) {
        if (displayName == null || displayName.isEmpty()) return null;
        UUID match = null;
        for (GroupDirectoryEntry entry : directory.values()) {
            if (!displayName.equals(entry.identity().displayName())) continue;
            if (match != null) return null;
            match = entry.identity().id();
        }
        if (match == null) return null;
        GroupDirectoryEntry entry = directory.get(match);
        return entry != null && entry.legacyHistoryAllowed() ? match : null;
    }

    /** Disconnect / world switch: forget everything so state can't leak. */
    public static void reset() {
        enabled = false;
        knownGroups.clear();
        myGroups.clear();
        CHANNELS.reset();
        v2Acknowledged = false;
        v2ServerKnown = false;
        directory.clear();
        backfillCursor.clear();
        backfillFloor.clear();
        backfillDone.clear();
        invitations.clear();
        directoryVersion++;
    }

    public static boolean isPseudoTab(String tab) {
        return TAB_WORLD.equals(tab) || TAB_SYSTEM.equals(tab);
    }

    /** Recipient-scoped invitations from the latest v2 directory (read-only). */
    public static List<GroupInvitationSummary> invitations() {
        return Collections.unmodifiableList(invitations);
    }

    /** Monotonic counter over directory/negotiation changes; see {@link #directoryVersion}. */
    public static long directoryVersion() {
        return directoryVersion;
    }

    /**
     * Pre-2.5 name-keyed tabs: the groups the server listed as ours
     * ({@link #myGroups}) that no current directory entry covers by display name.
     * On a v2 connection {@code myGroups} is rebuilt from the directory, so every
     * name is covered and this is empty — a group with a stable-id tab can never
     * get a second, name-keyed tab. On a v1-only connection (no ACK) there is no
     * directory, so the names pass through: the tab key is the display name, the
     * same key {@link #matchesTab} already resolves, so the tab strip and the
     * browser can open such a group and the send path routes it by name.
     */
    public static List<String> legacyTabs() {
        if (!enabled || myGroups.isEmpty()) return List.of();
        Set<String> covered = new LinkedHashSet<>();
        for (GroupDirectoryEntry entry : directory.values()) {
            covered.add(entry.identity().displayName());
        }
        List<String> out = new ArrayList<>();
        for (String name : myGroups) {
            if (name == null || name.isEmpty() || covered.contains(name)) continue;
            out.add(name);
        }
        return out;
    }

    /** Display label for a tab key: a group's current name, or the legacy name as-is. */
    public static String tabDisplayName(String tab) {
        if (tab == null || isPseudoTab(tab)) return null;
        UUID id = parseUuid(tab);
        if (id == null) return tab;
        GroupDirectoryEntry entry = directory.get(id);
        return entry == null ? null : entry.identity().displayName();
    }

    /** Every tab key that currently names something (pseudo ids + directory + legacy). */
    private static java.util.Set<String> validTabs() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.add(TAB_ALL);
        keys.add(TAB_WORLD);
        keys.add(TAB_SYSTEM);
        keys.addAll(knownGroups);
        keys.addAll(myGroups);
        for (UUID id : directory.keySet()) keys.add(id.toString());
        return keys;
    }

    @SuppressWarnings("unused")
    private static boolean tabExists(String tab) {
        if (tab == null) return false;
        if (isPseudoTab(tab) || TAB_ALL.equals(tab)) return true;
        UUID id = parseUuid(tab);
        if (id != null) return directory.containsKey(id);
        // A legacy tab is keyed by a myGroups name (see legacyTabs), so both the
        // advertised list and our own list keep the selection meaningful.
        return knownGroups.contains(tab) || myGroups.contains(tab);
    }

    /** A pre-v2 name selection upgrades to the stable id when exactly one group carries it. */
    private static String upgradeLegacyTab(String legacyName) {
        if (legacyName == null) return null;
        String match = null;
        for (GroupDirectoryEntry entry : directory.values()) {
            if (!legacyName.equals(entry.identity().displayName())) continue;
            if (match != null) return null;
            match = entry.identity().id().toString();
        }
        return match;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    // ==== Pure classification (unit-tested; no Minecraft statics) ====

    /** Which tab a message belongs to. Explicit group metadata wins over the
     *  system flag — group messages are always delivered as player messages,
     *  but a system flag set by another path must not steal them out of their tab.
     *  With the v2 directory authoritative, a group row keys on its stable id so
     *  the tab survives a rename; before that it keys on the stored name. */
    public static String channelOf(ChatMessage msg) {
        if (msg == null) return TAB_WORLD;
        if (msg.group() != null && !msg.group().isEmpty()) {
            UUID id = msg.groupId();
            if (id != null && v2ServerKnown) return id.toString();
            return msg.group();
        }
        if (msg.isSystem()) return TAB_SYSTEM;
        return TAB_WORLD;
    }

    /** Tab filter over the public (non-whisper) message list.
     *  「全部」= world + system only: group messages stay quarantined in their
     *  own tab (user report 2026-09-09 — mixing private group chatter into the
     *  default view read as "leaking into the world channel").
     *  Rows the current directory does not authorize are dropped here too, so a
     *  cache loaded from another server's session can't leak into any tab. */
    public static List<ChatMessage> filterMessages(List<ChatMessage> publicMessages, String activeTab) {
        if (activeTab == null) return publicMessages;
        List<ChatMessage> out = new ArrayList<>();
        boolean pseudo = TAB_ALL.equals(activeTab) || isPseudoTab(activeTab);
        for (ChatMessage m : publicMessages) {
            // Authorization first: a row the current directory does not grant (left
            // group, earlier membership window, another server's session) is hidden
            // in every tab. Then the shared channel decision: the pseudo tabs route
            // through MessageFilter (single source), a group key through matchesTab,
            // which also resolves a pre-2.5 name-keyed tab.
            if (!isVisible(m)) continue;
            boolean visible = pseudo
                ? MessageFilter.visible(channelOf(m), m.isSystem(), activeTab)
                : matchesTab(m, activeTab);
            if (visible) out.add(m);
        }
        return out;
    }

    /**
     * A row belongs to a tab when its stable key matches (v2 id), or — for the
     * pre-2.5 name-keyed tabs — when its stored display name matches. The id path
     * keeps the tab stable across a rename; the name path keeps a legacy tab
     * working until the directory upgrades it to an id.
     */
    public static boolean matchesTab(ChatMessage msg, String tab) {
        if (tab == null) return false;
        if (tab.equals(channelOf(msg))) return true;
        if (isPseudoTab(tab)) return false;
        return msg.group() != null && msg.group().equals(tab);
    }
}
