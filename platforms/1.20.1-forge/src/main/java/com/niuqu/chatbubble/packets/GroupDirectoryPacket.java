package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupIdentity;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C recipient-scoped group directory (v2). Replaces the public v1
 * {@link GroupListPacket} for connections that ACKed the handshake: it lists
 * only the groups this recipient belongs to, keyed by the stable group UUID, so
 * a display name can be reused without another group's history riding along.
 *
 * <p>Receiving it starts the authorized history backfill — the paging requests
 * are sent by the packet handler, the authorization decision lives in
 * {@link GroupChannelState}.
 */
public class GroupDirectoryPacket {

    private static final int MAX_NAME = 64;
    // Decode-side caps: a hostile server must not balloon client memory, but the
    // caps must still cover every directory a valid server may hold. group_max_count
    // tops out at 500 and group_max_members at 1000, and one live invitation per
    // (group, invitee) bounds a recipient's invitations by the group count as well.
    private static final int MAX_GROUPS = 500;
    private static final int MAX_MEMBERS_PER_GROUP = 1000;
    private static final int MAX_INVITATIONS = 500;

    private final List<GroupDirectoryEntry> entries;
    private final List<GroupInvitationSummary> invitations;

    public GroupDirectoryPacket(List<GroupDirectoryEntry> entries, List<GroupInvitationSummary> invitations) {
        this.entries = entries;
        this.invitations = invitations;
    }

    public List<GroupDirectoryEntry> entries() {
        return entries;
    }

    public List<GroupInvitationSummary> invitations() {
        return invitations;
    }

    public static void encode(GroupDirectoryPacket packet, FriendlyByteBuf buf) {
        List<GroupDirectoryEntry> entries = packet.entries != null ? packet.entries : List.of();
        int entryCount = 0;
        for (GroupDirectoryEntry entry : entries) if (entry != null) entryCount++;
        buf.writeVarInt(entryCount);
        for (GroupDirectoryEntry entry : entries) {
            if (entry == null) continue;
            buf.writeUUID(entry.identity().id());
            buf.writeUtf(entry.identity().displayName(), MAX_NAME);
            buf.writeUUID(entry.ownerId());
            List<UUID> members = entry.memberIds();
            buf.writeVarInt(members.size());
            for (UUID member : members) buf.writeUUID(member);
            buf.writeLong(entry.visibleFromSequence());
            buf.writeBoolean(entry.legacyHistoryAllowed());
            buf.writeLong(entry.latestSequence());
            buf.writeLong(entry.retainedFromSequence());
        }
        List<GroupInvitationSummary> invitations = packet.invitations != null ? packet.invitations : List.of();
        int invitationCount = 0;
        for (GroupInvitationSummary invitation : invitations) if (invitation != null) invitationCount++;
        buf.writeVarInt(invitationCount);
        for (GroupInvitationSummary invitation : invitations) {
            if (invitation == null) continue;
            buf.writeUUID(invitation.id());
            buf.writeUUID(invitation.groupId());
            buf.writeUtf(invitation.groupName(), MAX_NAME);
            buf.writeUUID(invitation.inviterId());
            buf.writeLong(invitation.createdAtMs());
            buf.writeLong(invitation.expiresAtMs());
        }
    }

    public static GroupDirectoryPacket decode(FriendlyByteBuf buf) {
        // Read every declared row, member and invitation off the wire before applying
        // the caps: a surplus row's bytes would otherwise be left in the buffer, and
        // the invitation list that follows would be parsed at the wrong offset. Rows
        // and member UUIDs that will not be kept are consumed without allocating.
        int groupCount = readCount(buf);
        List<GroupDirectoryEntry> entries = new ArrayList<>(Math.min(groupCount, MAX_GROUPS));
        for (int i = 0; i < groupCount; i++) {
            UUID id = buf.readUUID();
            String displayName = buf.readUtf(MAX_NAME);
            UUID ownerId = buf.readUUID();
            int memberCount = readCount(buf);
            boolean keep = entries.size() < MAX_GROUPS;
            List<UUID> members = keep ? new ArrayList<>(Math.min(memberCount, MAX_MEMBERS_PER_GROUP)) : null;
            for (int m = 0; m < memberCount; m++) {
                UUID member = buf.readUUID();
                if (members != null && members.size() < MAX_MEMBERS_PER_GROUP) members.add(member);
            }
            long visibleFrom = buf.readLong();
            boolean legacyAllowed = buf.readBoolean();
            long latest = buf.readLong();
            long retained = buf.readLong();
            if (!keep) continue;
            GroupDirectoryEntry entry = directoryEntry(id, displayName, ownerId, members,
                visibleFrom, legacyAllowed, latest, retained);
            // A malformed row is dropped instead of aborting the whole directory.
            if (entry != null) entries.add(entry);
        }
        int invitationCount = readCount(buf);
        List<GroupInvitationSummary> invitations = new ArrayList<>(Math.min(invitationCount, MAX_INVITATIONS));
        for (int i = 0; i < invitationCount; i++) {
            UUID id = buf.readUUID();
            UUID groupId = buf.readUUID();
            String groupName = buf.readUtf(MAX_NAME);
            UUID inviterId = buf.readUUID();
            long createdAt = buf.readLong();
            long expiresAt = buf.readLong();
            if (invitations.size() >= MAX_INVITATIONS) continue;
            GroupInvitationSummary summary = invitationSummary(id, groupId, groupName, inviterId,
                createdAt, expiresAt);
            if (summary != null) invitations.add(summary);
        }
        return new GroupDirectoryPacket(entries, invitations);
    }

    private static int readCount(FriendlyByteBuf buf) {
        return Math.max(buf.readVarInt(), 0);
    }

    /** One row; an inconsistent row (blank name, no owner among the members,
     *  out-of-range sequence) is dropped instead of killing the whole directory. */
    private static GroupDirectoryEntry directoryEntry(UUID id, String displayName, UUID ownerId,
                                                      List<UUID> members, long visibleFrom,
                                                      boolean legacyAllowed, long latest, long retained) {
        try {
            return new GroupDirectoryEntry(new GroupIdentity(id, displayName), ownerId, members,
                visibleFrom, legacyAllowed, latest, retained);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static GroupInvitationSummary invitationSummary(UUID id, UUID groupId, String groupName,
                                                            UUID inviterId, long createdAt, long expiresAt) {
        try {
            return new GroupInvitationSummary(id, groupId, groupName, inviterId, createdAt, expiresAt);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> applyClientDirectory(entries, invitations))
        );
        ctx.get().setPacketHandled(true);
    }

    /**
     * Client-side apply of one complete directory, also used by the reassembled
     * chunk path. A full snapshot is newer than any transfer still in flight, so a
     * partly received chunk set is dropped instead of racing this one; the legacy
     * rows are then bound to their grants and the authorized backfill is requested.
     */
    public static void applyClientDirectory(List<GroupDirectoryEntry> entries,
                                            List<GroupInvitationSummary> invitations) {
        // TODO(M5): the client group state machine applies this recipient-scoped
        // directory (name-resolved grants, backfill requests) and the v2 client UI
        // lands in M5/M6. A complete snapshot supersedes any in-flight chunk set:
        // resetTransfers() runs here once that path is wired.
        GroupDirectoryChunkPacket.resetTransfers();
    }
}
